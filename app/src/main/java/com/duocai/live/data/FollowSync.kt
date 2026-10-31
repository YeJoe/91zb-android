package com.tm.live.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 本地关注的 GitHub Gist 同步。
 *
 * 为什么是 Gist 而不是「github raw」直传文件：
 * - Gist 有独立的「只读 gist」权限范围，Token 只要 `gist` 一项，泄漏了也动不了主仓库；
 * - Gist 的 raw 地址 `https://gist.githubusercontent.com/<user>/<id>/raw/<file>` 正好是
 *   「github raw」语义，跨设备读同一个地址即可；
 * - 不需要 commit、不需要 `repo` 权限，创建/更新都是一次 HTTP 调用。
 *
 * 同步语义（双向合并，不是覆盖）：
 *   本地 push 上去 → 再从远端 pull 回来合并 → 两设备最终收敛到同一份关注。
 * 见 [FollowStore.mergeRemote]：按 id 求并集，ts 新的胜出，只加不删。
 *
 * 凭证全在 [Prefs]（ghToken 走 Keystore 加密）。本文件不缓存任何 Token。
 */
object FollowSync {

    private const val API = "https://api.github.com"
    private const val FILE = "tm_live_follows.json"
    private const val UA = "tm-live-android"

    data class Result(val ok: Boolean, val msg: String)

    /**
     * 推 + 拉 一次跑完。
     *
     * @param pushFirst 先把自己这份推上去（默认 true）。关掉则只从远端拉。
     * @return 成功失败 + 给用户的文案（同时会写进 [Prefs.syncMsg]）
     */
    suspend fun sync(
        prefs: Prefs,
        store: FollowStore,
        pushFirst: Boolean
    ): Result = withContext(Dispatchers.IO) {
        val token = prefs.ghToken
        if (token.isBlank()) {
            return@withContext Result(false, "未填 GitHub Token（设置 → 本地关注同步）").also {
                prefs.syncMsg = it.msg
            }
        }
        try {
            if (pushFirst) pushGist(token, prefs, store)
            val remote = pullGist(token, prefs)
            if (remote != null) {
                store.mergeRemote(remote)
                // 合并完再推一次，保证远端也是收敛后的全集
                pushGist(token, prefs, store)
            }
            val n = store.ids().size
            val t = if (prefs.syncAt > 0) fmtTime(prefs.syncAt) else "—"
            val msg = "已同步 · 本地共 $n 条 · $t"
            prefs.syncMsg = msg
            Result(true, msg)
        } catch (e: Exception) {
            val msg = "同步失败：${e.message?.take(80) ?: e.javaClass.simpleName}"
            prefs.syncMsg = msg
            Result(false, msg)
        }
    }

    /** 只推不上拉（手动「上传」按钮用） */
    suspend fun push(prefs: Prefs, store: FollowStore): Result = withContext(Dispatchers.IO) {
        val token = prefs.ghToken
        if (token.isBlank()) return@withContext Result(false, "未填 GitHub Token").also { prefs.syncMsg = it.msg }
        try {
            pushGist(token, prefs, store)
            val msg = "已上传 ${store.ids().size} 条到 Gist"
            prefs.syncMsg = msg
            Result(true, msg)
        } catch (e: Exception) {
            val msg = "上传失败：${e.message?.take(80) ?: e.javaClass.simpleName}"
            prefs.syncMsg = msg
            Result(false, msg)
        }
    }

    // ---- 内部实现 ----

    private fun pushGist(token: String, prefs: Prefs, store: FollowStore) {
        val content = store.exportJson()
        val body = JSONObject().apply {
            put("public", false)
            put("files", JSONObject().apply {
                put(FILE, JSONObject().apply { put("content", content) })
            })
        }.toString()

        val (code, resp, etag) = if (prefs.ghGistId.isBlank()) {
            request("POST", "$API/gists", token, body)
        } else {
            request("PATCH", "$API/gists/${prefs.ghGistId}", token, body)
        }
        when (code) {
            200, 201 -> {
                val id = JSONObject(resp).optString("id")
                if (!id.isNullOrBlank()) prefs.ghGistId = id
                prefs.syncAt = System.currentTimeMillis()
                // 真实的 ETag 在响应头里（request 第三返回值），下次拉取带上做 If-None-Match 短路
                if (!etag.isNullOrBlank()) prefs.syncEtag = etag
            }
            else -> error("HTTP $code: ${resp.take(120)}")
        }
    }

    /**
     * 拉取 Gist 内容。返回解析后的 Follow 列表；404（gist 被删）或空内容返回 null。
     * 带 If-None-Match：304 表示服务端那份没变，直接返回 null（本地已是它上次拉到的状态）。
     */
    private fun pullGist(token: String, prefs: Prefs): List<Follow>? {
        val id = prefs.ghGistId
        if (id.isBlank()) return null
        val (code, resp, etag) = request("GET", "$API/gists/$id", token, null, prefs.syncEtag)
        when (code) {
            304 -> return null
            200 -> {
                val files = JSONObject(resp)
                    .optJSONObject("files")?.optJSONObject(FILE)
                val content = files?.optString("content")?.takeIf { it.isNotBlank() } ?: return null
                // 响应头里的 ETag 写回，下次拉取直接 If-None-Match 短路，省一次下载 + 配额
                if (!etag.isNullOrBlank()) prefs.syncEtag = etag
                return parseFollows(content)
            }
            else -> error("HTTP $code: ${resp.take(120)}")
        }
    }

    /** 把 Gist 里的 JSON 数组解析成 Follow 列表（坏的字段跳过，不整段失败） */
    private fun parseFollows(json: String): List<Follow> {
        val out = ArrayList<Follow>(8)
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                if (id <= 0L) continue
                out.add(
                    Follow(
                        id = id,
                        name = o.optString("name"),
                        cat = o.optString("cat"),
                        ts = o.optLong("ts", System.currentTimeMillis())
                    )
                )
            }
        }
        return out
    }

    // 极简 HttpURLConnection 封装：返回 (code, body, etagHeader)
    private fun request(
        method: String,
        url: String,
        token: String,
        body: String?,
        ifNoneMatch: String? = null
    ): Triple<Int, String, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        if (ifNoneMatch != null && ifNoneMatch.isNotBlank()) {
            conn.setRequestProperty("If-None-Match", ifNoneMatch)
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { os ->
                os.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val code = conn.responseCode
        val etag = conn.getHeaderField("ETag")
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText) ?: ""
        conn.disconnect()
        return Triple(code, resp, etag)
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return "${h}时${m}分前"
    }
}
