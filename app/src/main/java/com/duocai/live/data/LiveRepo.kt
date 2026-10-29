package com.duocai.live.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 实时数据源：直连 App 隧道端口（127.0.0.1:<port>），不走代理、不读 JSON 文件。
 *
 * 原理（见 docs/明文长连接通道.md §2 / §10.3）：
 *   kiwi SDK 把 `http://kiwi_live` 之类占位符改写成 `127.0.0.1:<端口>`，
 *   这个端口是隧道在**本机**开的明文 HTTP 入口。只要 App 和它跑在同一台设备上，
 *   直接用 HttpURLConnection 就能打到 `/room/list`，**不需要 /go 加密路径**。
 *
 * 需要两个凭证（从源 App 取一次，缓存在 Prefs）：
 *   - appToken : 32 位 hex，隧道 URL 的 ?token=，quick-login 换来
 *   - jwe      : authorization 头的 Bearer 值（prefs 里 MAINUSER_DATA.token 原样）
 *
 * 端口探测：读 /proc/net/tcp 找 LISTEN(0A) 端口，逐个试 /room/list，
 * 谁能返回 code=0 就用谁（隧道里挂了十几个 vhost）。
 */
class LiveRepo(private val context: Context, private val prefs: Prefs) {

    /** 一次探测的存活时间，避免每帧都扫 /proc */
    @Volatile private var cachedPort: Int = 0
    @Volatile private var portCheckedAt: Long = 0L

    private val cacheFile = java.io.File(context.filesDir, "rooms_live.json")

    // ------------------------------------------------------------------ 端口
    /** 读 /proc/net/tcp 的 LISTEN 端口（0A 状态） */
    private fun listenPorts(): List<Int> {
        val out = ArrayList<Int>()
        for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                java.io.File(path).forEachLine { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size > 3 && cols[3] == "0A") {
                        val hexPort = cols[1].substringAfterLast(':')
                        hexPort.toIntOrNull(16)?.let { out.add(it) }
                    }
                }
            }
        }
        return out.distinct().sorted()
    }

    /** 找一个能对 /room/list 返回 code=0 的端口 */
    private fun probePort(): Int {
        val now = System.currentTimeMillis()
        if (cachedPort != 0 && now - portCheckedAt < 60_000) return cachedPort

        val preferred = prefs.tunnelPort
        val candidates = buildList {
            if (preferred > 0) add(preferred)
            addAll(listenPorts().filter { it in 1024..65535 })
        }.distinct()

        for (p in candidates) {
            val r = runCatching { post(p, "/room/list", """{"pageIndex":1,"menuId":0}""", timeout = 2500) }
                .getOrNull() ?: continue
            if (r.optInt("code", -1) == 0) {
                cachedPort = p
                portCheckedAt = now
                if (prefs.tunnelPort != p) prefs.tunnelPort = p
                return p
            }
        }
        throw IOException("隧道端口不可用：源 App 没在跑，或 token 失效（已试 ${candidates.size} 个端口）")
    }

    // ------------------------------------------------------------------ HTTP
    private fun post(port: Int, path: String, body: String, timeout: Int = 8000): JSONObject {
        val t = prefs.appToken
        if (t.isBlank()) throw IOException("还没填 appToken（见设置页）")
        val conn = (URL("http://127.0.0.1:$port$path?token=$t").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            // 这几个头是照抄 Dart HttpClient 的，缺一个服务端就挑刺
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("accept-encoding", "gzip")
            conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
            val jwe = prefs.jwe
            if (jwe.isNotBlank()) conn.setRequestProperty("authorization", "Bearer $jwe")

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw IOException("HTTP $code")
            var raw = stream.use { it.readBytes() }
            // 服务端有时 gzip 有时明文
            if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                raw = GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            }
            val text = String(raw, Charsets.UTF_8)
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(160)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 列表
    /**
     * 拉在线房间：
     *  1) /room/list          → result = 房间 ID 列表
     *  2) /room/get_list_by_ids → 每间的 name/menu_id/chess_id/on_air/player_count
     */
    suspend fun fetch(): Result<SourceData> = withContext(Dispatchers.IO) {
        runCatching {
            val port = probePort()

            val listResp = post(port, "/room/list", """{"pageIndex":1,"menuId":0}""")
            if (listResp.optInt("code", -1) != 0) {
                throw IOException("room/list code=${listResp.optInt("code")} msg=${listResp.optString("msg")}")
            }
            val ids = listResp.optJSONArray("result") ?: JSONArray()
            if (ids.length() == 0) return@runCatching SourceData(emptyList(), SourceMeta(rtmpDomain = prefs.rtmpDomain))

            val idText = (0 until ids.length()).joinToString(",") { ids.optLong(it).toString() }
            val detail = post(port, "/room/get_list_by_ids", JSONObject().put("ids", idText).toString(), timeout = 12000)
            val arr = detail.optJSONArray("rooms") ?: JSONArray()

            val rooms = ArrayList<Room>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optLong("id", 0L)
                if (id <= 0L) continue
                rooms.add(
                    Room(
                        id = id,
                        name = r.optString("name").ifBlank { "房间 $id" },
                        cat = catOf(r),
                        online = r.optInt("player_count", 0),
                        city = r.optString("city_show").ifBlank { r.optString("city") },
                        ad = r.optInt("chess_id", 0) != 3,
                        coins = r.optInt("live_fee", 0)
                    )
                )
            }

            val meta = SourceMeta(
                rtmpDomain = prefs.rtmpDomain,
                fetchedAt = System.currentTimeMillis()
            )
            val data = SourceData(rooms, meta)
            runCatching { writeCache(detail) }
            data
        }
    }

    /** 分类：真人直播(chess_id=3 且 menu_id>=100) 才算能拉流 */
    private fun catOf(r: JSONObject): String {
        val chess = r.optInt("chess_id", 0)
        val menu = r.optInt("menu_id", 0)
        return when {
            chess == 3 && menu >= 100 -> "直播"
            chess == 8 -> "棋牌"
            else -> "其他"
        }
    }

    /** 冷启动先用上次的原始响应铺屏，避免白屏 */
    fun loadCache(): SourceData? = runCatching {
        if (!cacheFile.exists()) return@runCatching null
        val arr = JSONObject(cacheFile.readText()).optJSONArray("rooms") ?: return@runCatching null
        val rooms = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optLong("id", 0L)
            if (id <= 0L) continue
            rooms.add(
                Room(
                    id = id,
                    name = r.optString("name").ifBlank { "房间 $id" },
                    cat = catOf(r),
                    online = r.optInt("player_count", 0),
                    city = r.optString("city_show").ifBlank { r.optString("city") },
                    ad = r.optInt("chess_id", 0) != 3,
                    coins = r.optInt("live_fee", 0)
                )
            )
        }
        if (rooms.isEmpty()) null
        else SourceData(rooms, SourceMeta(rtmpDomain = prefs.rtmpDomain, fromCache = true))
    }.getOrNull()

    private fun writeCache(detail: JSONObject) {
        cacheFile.writeText(detail.toString())
    }
}
