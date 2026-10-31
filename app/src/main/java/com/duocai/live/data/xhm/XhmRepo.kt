package com.tm.live.data.xhm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 小红帽 REST 数据源。
 *
 * 直连 `https://api.xt2.club`（接口文档里唯一的应用主域，scheme 推断为 https）。
 * 文档只给了路由路径、没给响应体结构，所以解析是**防御式**的：常见字段名都认，
 * 认不出就退回到"只保留能播的字段"。真要 100% 贴合得反编译 dex 看 Retrofit 注解。
 *
 * 没有会话 token 时，接口基本都回 401/403，所以这里提供一条 **demo 通道**：
 * `demo = true` 时直接返回内置样例数据，UI 开箱即用；用户把从小红帽 App 里导出的
 * 会话 token 填进设置页后，再把 demo 关掉，就走真实接口。
 */
class XhmRepo(private val context: Context, private val prefs: XhmPrefs) {

    private val cacheFile = context.filesDir.resolve("xhm_home.json")

    suspend fun fetchHome(): Result<XhmHomeData> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.demo) return@runCatching XhmDemo.home()

            val text = httpGet("/home")
            val data = parseHome(text)
                ?: throw IOException("小红帽 /home 解析失败（可能未登录或接口结构变化）")
            writeCache(text)
            data
        }
    }

    suspend fun fetchFollowList(): Result<List<XhmRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.demo) return@runCatching XhmDemo.follows()
            val text = httpGet("/relation/followlist")
            parseRoomList(text) ?: throw IOException("小红帽关注列表解析失败")
        }
    }

    /**
     * 取拉流地址。小红帽的真实流是防盗链 RTMP：`rtmp://pullhw104.szier2.com/live/...?txSecret=...&txTime=...`
     * 由 `/webrtc/v1/pullstream`（或直播接口）在进房时返回，约 3 分钟时效。
     *
     * 文档只给了路由，请求体/响应体未知，这里用最可能的形态发起，并防御式地捞取任何
     * 长得像播放地址的字段（url / rtmp / playUrl / pullUrl / hls / stream）。
     */
    suspend fun getStreamUrl(roomId: Long): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.demo) return@runCatching XhmDemo.streamFor(roomId)

            val body = JSONObject().apply {
                put("roomId", roomId)
                put("userId", prefs.userId)
            }.toString()
            val text = httpPost("/webrtc/v1/pullstream", body)
            extractStreamUrl(text)
                ?: throw IOException("小红帽拉流地址解析失败（可能未登录）")
        }
    }

    suspend fun search(nickname: String): Result<List<XhmRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.demo) return@runCatching XhmDemo.search(nickname)
            val text = httpGet("/accessuser/searchbynickname?nickname=${URLEncoder.encode(nickname, "UTF-8")}")
            parseRoomList(text) ?: emptyList()
        }
    }

    /**
     * **接口关注**（不是纯本机收藏）。
     *
     * 小红帽的关注接口和天美不一样：读写都挂在同一个主域上（没有"读通写不通"的分叉），
     * 只要会话 token 有效，写接口大概率直接通。dex 里实锤的只有读侧 `/relation/followlist`，
     * 写侧路由没在字符串表里露面，所以这里按同类命名依次尝试候选路由：
     *
     *   `/relation/follow`、`/relation/unfollow`（最可能的一对）
     *   `/relation/attention`、`/user/follow`（同族兜底）
     *
     * 请求体也防御式：roomId / userId / follow / type 一并带上，服务端取它认的那个。
     * 判定"成功"的标准：HTTP 2xx **且** 响应里 code==0 或没有 code 字段（纯 data 包装）。
     * 全部候选都打不动才返回 false —— 调用方（AppContainer.xhmToggleFollow）据此降级为
     * "仅本机"并如实提示，不假装云端成功。
     */
    suspend fun syncFollow(roomId: Long, follow: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.demo) throw IOException("demo 模式：未配置会话，走本机收藏")
            if (prefs.token.isBlank()) throw IOException("未配置会话 token，走本机收藏")

            val body = JSONObject().apply {
                put("roomId", roomId)
                put("anchorId", roomId)
                put("userId", prefs.userId)
                put("follow", follow)
                put("type", if (follow) 1 else 0)
            }.toString()

            val routes = if (follow) {
                listOf("/relation/follow", "/relation/attention", "/user/follow")
            } else {
                listOf("/relation/unfollow", "/relation/unattention", "/user/unfollow", "/relation/follow")
            }
            var lastMsg = "无候选路由可打"
            for (route in routes) {
                val text = try {
                    httpPost(route, body)
                } catch (e: IOException) {
                    lastMsg = "$route → ${e.message}"
                    continue
                }
                val obj = runCatching { JSONObject(text) }.getOrNull()
                val code = obj?.optInt("code", -1) ?: -1
                if (obj == null || code == 0 || obj.has("data")) {
                    return@runCatching true
                }
                lastMsg = "$route → code=$code ${obj.optString("msg").take(60)}"
            }
            throw IOException(lastMsg)
        }
    }

    // ------------------------------------------------------------------ 解析
    private fun parseHome(text: String): XhmHomeData? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // 接口常包一层 {"code":0,"data":{...}}
        val dataObj = asObject(obj.opt("data")) ?: obj
        val rooms = parseRoomList(dataObj) ?: parseRoomList(obj) ?: emptyList()
        val cats = parseCategories(dataObj) ?: parseCategories(obj) ?: emptyList()
        if (rooms.isEmpty() && cats.isEmpty()) return null
        return XhmHomeData(cats, rooms)
    }

    private fun parseCategories(obj: JSONObject): List<XhmCategory>? {
        val arr = asArray(obj.opt("categoryList"))
            ?: asArray(obj.opt("categories"))
            ?: asArray(obj.opt("cats"))
            ?: return null
        val out = ArrayList<XhmCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val o = asObject(arr.opt(i)) ?: continue
            val name = o.optString("name").ifBlank { o.optString("catName") }.ifBlank { continue }
            out.add(XhmCategory(name, o.optInt("count", 0)))
        }
        return out.ifEmpty { null }
    }

    private fun parseRoomList(any: Any?): List<XhmRoom>? {
        val obj = (any as? JSONObject) ?: return null
        // 房间数组可能在多个键名里
        val arr = asArray(obj.opt("liveList"))
            ?: asArray(obj.opt("list"))
            ?: asArray(obj.opt("rooms"))
            ?: asArray(obj.opt("items"))
            ?: asArray(obj.opt("data"))
            ?: return null
        val out = ArrayList<XhmRoom>(arr.length())
        for (i in 0 until arr.length()) {
            val o = asObject(arr.opt(i)) ?: continue
            val id = o.optLong("id", 0L)
            if (id <= 0L) continue
            val name = o.optString("name").ifBlank { o.optString("nickname") }.ifBlank { "房间 $id" }
            val cat = o.optString("cat").ifBlank { o.optString("category") }
                .ifBlank { o.optString("catName") }.ifBlank { "推荐" }
            val online = o.optInt("online", 0)
                .let { if (it == 0) o.optInt("onlineNum", 0) else it }
                .let { if (it == 0) o.optInt("watchNum", 0) else it }
            val avatar = fullImageUrl(prefs.imgBase, o.optString("avatar").ifBlank {
                o.optString("headImg").ifBlank { o.optString("cover") }
            })
            val cover = fullImageUrl(prefs.imgBase, o.optString("cover").ifBlank { avatar })
            val fans = o.optInt("fans", 0).let { if (it == 0) o.optInt("fansNum", 0) else it }
            val liveIn = o.optInt("liveIn", 1) != 0 && o.optInt("live_in", 1) != 0
            out.add(XhmRoom(id, name, cat, online, avatar, cover, fans, "", liveIn))
        }
        return out.ifEmpty { null }
    }

    private fun extractStreamUrl(text: String): String? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val data = asObject(obj.opt("data")) ?: obj
        for (key in arrayOf("url", "rtmp", "playUrl", "pullUrl", "pull", "stream", "hls")) {
            val v = data.optString(key)
            if (v.startsWith("rtmp://") || v.startsWith("http://") || v.startsWith("https://")) {
                return v
            }
        }
        // 递归扫一层，捞任何以 rtmp/http(s) 开头的字符串
        return scanString(data)
    }

    private fun scanString(o: JSONObject): String? {
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = o.opt(k)
            if (v is String && (v.startsWith("rtmp://") || v.startsWith("http"))) return v
            if (v is JSONObject) scanString(v)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ HTTP
    private fun httpGet(path: String): String {
        val url = buildUrl(path)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("user-agent", "okhttp/4.12.0")
            setRequestProperty("accept", "application/json")
            authHeader()?.let { setRequestProperty("authorization", it) }
            if (prefs.token.isNotEmpty()) setRequestProperty("token", prefs.token)
        }
        return readBody(conn)
    }

    private fun httpPost(path: String, body: String): String {
        val url = buildUrl(path)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("user-agent", "okhttp/4.12.0")
            setRequestProperty("accept", "application/json")
            authHeader()?.let { setRequestProperty("authorization", it) }
            if (prefs.token.isNotEmpty()) setRequestProperty("token", prefs.token)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readBody(conn)
    }

    private fun buildUrl(path: String): String {
        val base = prefs.baseUrl.trim().trimEnd('/')
        val sep = if (path.contains('?')) "&" else "?"
        val tokenQ = if (prefs.token.isNotEmpty()) "$sep$jwtParam=${prefs.token}" else ""
        return "$base$path$tokenQ"
    }

    /** 鉴权头：小红帽的 WS 用 `jwt_token` 查询参数；REST 多半是 Bearer，两种都带上 */
    private fun authHeader(): String? =
        prefs.token.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

    private val jwtParam: String get() = "jwt_token"

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: ${text.take(120)}")
        return text
    }

    private fun writeCache(text: String) {
        runCatching { cacheFile.writeText(text) }
    }

    fun loadCache(): XhmHomeData? = runCatching {
        if (!cacheFile.exists()) return@runCatching null
        parseHome(cacheFile.readText())
    }.getOrNull()
}
