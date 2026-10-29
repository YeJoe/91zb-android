package com.duocai.live.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 实时数据源：直连源 App 的隧道端口（127.0.0.1:<port>），自己发 HTTP，不依赖源 App 的界面。
 *
 * ## 8.5.3 的接口现状（2026-10-29 抓包 + 全端口扫描确认）
 *
 * | 接口 | 状态 |
 * |---|---|
 * | `POST /room/list` | **已下线**。17 个本地端口全 404；源 App 冷启动全程一次都没调它 |
 * | `POST /login/get_data_by_tbname {"tb_name":"room"}` | `{"code":-2,"msg":"查询失败"}`，room 是动态表不对外 |
 * | `POST /room/get_list_by_ids {"ids":"a,b,c"}` | ✅ **唯一可用**，一次 200 个 id 约 0.5s，返回完整 Room |
 * | `POST /login/get_data_by_tbname {"tb_name":"lobby_menu"}` | ✅ 分类表（关注/推荐/热门/附近/星秀/…） |
 * | `POST /user_follow/my_follow` | ✅ 关注的主播（podcast_id），但没有房间号 |
 *
 * 也就是说：**这一版没有"房间列表接口"了**。源 App 自己维护一张
 * `localData:room.<uid>` 的本地表（实测 1972 间 / 1956 在播），靠 WS `ctl=room`
 * 推送增量维护，切分类只是对这张表做本地过滤。
 *
 * ## 所以这里的做法
 *
 * 1. **id 池**：`assets/room_ids.txt` 一份种子（从源 App 的本地表导出的 1972 个房间号），
 *    跑起来后落在 `filesDir/room_ids.txt`，之后只增不减。
 * 2. **刷新**：把 id 池按 250 个一批丢给 `/room/get_list_by_ids`，拿回真实的
 *    `on_air / player_count / menu_id / city_show`，只留 `on_air=1`。
 *    这就是"手动刷新拿到最新直播间"——人数、在播状态全是服务端实时值。
 * 3. **端口自动探测**：隧道端口每次源 App 重启都会变，所以不能写死；
 *    拿 `/room/get_list_by_ids` 打一遍监听端口，认 `code` 字段的那个 vhost。
 *
 * 房间号是主播建房时就固定下来的（不是每次开播新分配），所以 id 池相当稳定；
 * 新主播才会带来新 id，池子只增不减不会误删。
 */
class LiveRepo(private val context: Context, private val prefs: Prefs) {

    /** 一次探测的存活时间，避免每帧都扫 /proc */
    @Volatile private var cachedPort: Int = 0
    @Volatile private var portCheckedAt: Long = 0L

    /** 最近一次成功使用的端口（0 = 还没连通过）。设置页拿它显示连接状态。 */
    @Volatile var lastPort: Int = 0
        private set

    /**
     * 外部指定优先尝试的端口。
     *
     * 内置隧道（[com.duocai.live.tunnel.KiwiSdk]）起来后由 [com.duocai.live.AppContainer]
     * 写进来，那样就不用再去 `/proc/net/tcp` 里瞎扫 —— 扫到的端口里只有个别挂业务 vhost，
     * 逐个试既慢又容易误判。0 = 没指定，走老逻辑。
     */
    @Volatile var preferPort: Int = 0

    /**
     * 源 App 账号关注的房间（`podcast_id` == 房间号）与昵称。
     * 由 [fetch] 每次刷新时更新，供「关注」页直接读。
     */
    @Volatile var followIds: Set<Long> = emptySet()
        private set
    @Volatile var followNames: Map<Long, String> = emptyMap()
        private set

    private val cacheFile = File(context.filesDir, "rooms_live.json")
    private val idPoolFile = File(context.filesDir, "room_ids.txt")

    /** 一批塞多少个 id。实测 200 → 108KB / 0.56s，250 仍在同一量级 */
    private val batchSize = 250

    // ------------------------------------------------------------------ 端口
    /** 读 /proc/net/tcp 的 LISTEN 端口（0A 状态） */
    private fun listenPorts(): List<Int> {
        val out = ArrayList<Int>()
        for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                File(path).forEachLine { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size > 3 && cols[3] == "0A") {
                        cols[1].substringAfterLast(':').toIntOrNull(16)?.let { out.add(it) }
                    }
                }
            }
        }
        // 5555 是 adbd，不是隧道
        return out.filter { it in 1024..65535 && it != 5555 }.distinct().sorted()
    }

    /**
     * 找一个真正挂 `/room` 前缀的 vhost（注意：注释里别写斜杠星号，Kotlin 的块注释是可嵌套的，
     * 那样会吃掉后面整段代码）。
     *
     * 判据不能用 `/room/list`（已下线，所有端口 404）。改用 `/room/get_list_by_ids` + 一个**真实房间号**：
     * 只有对的 vhost 才会回 `{"code":0,"rooms":[...]}`；错的 vhost 被 nginx 直接 404（HTML），
     * 或者只回 `{"code":-1,"msg":"ids is null"}` 这种空壳，都不认。
     */
    private fun probePort(force: Boolean = false, sample: Long? = null): Int {
        val now = System.currentTimeMillis()
        if (!force && cachedPort != 0 && now - portCheckedAt < 60_000) return cachedPort

        val test = sample ?: idPool().firstOrNull()
        if (test == null) throw IOException("房间号池是空的")

        val preferred = prefs.tunnelPort
        val candidates = buildList {
            // 内置隧道指定的端口排第一：它一定挂业务 vhost，命中即用
            if (preferPort > 0) add(preferPort)
            if (preferred > 0) add(preferred)
            addAll(listenPorts())
        }.distinct()

        for (p in candidates) {
            val obj = runCatching {
                post(p, "/room/get_list_by_ids", """{"ids":"$test"}""", timeout = 1500)
            }.getOrNull() ?: continue
            if (obj.has("rooms")) {
                cachedPort = p
                portCheckedAt = now
                lastPort = p
                if (prefs.tunnelPort != p) prefs.tunnelPort = p
                return p
            }
        }
        throw IOException("隧道端口不可用：源 App 没在后台跑，或 token/JWE 失效（已试 ${candidates.size} 个端口）")
    }

    // ------------------------------------------------------------------ HTTP
    /**
     * 发一个隧道请求。
     *
     * **大量实测（2026-10-29 22:00，源 App 重启后换端口复测）**：
     *
     * | 接口 | `?token=` | `authorization: Bearer JWE` |
     * |---|---|---|
     * | `/room/get_list_by_ids` | 不需要（传空/传垃圾都回 200 + rooms） | 不需要 |
     * | `/user_follow/my_follow` | 不需要 | **必需**（没有就回 `{"code":…,"msg":…}`） |
     *
     * 所以这里不再对 token 做非空校验 —— 之前那句「没填 Token 就报错」把最容易用的
     * 一条链路（看直播）挡住了，而它其实一个凭证都不需要。
     */
    private fun post(port: Int, path: String, body: String, timeout: Int = 8000): JSONObject {
        val t = prefs.appToken.trim()
        val query = if (t.isEmpty()) "" else "?token=$t"
        val conn = (URL("http://127.0.0.1:$port$path$query").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            // 这几个头是照抄 Dart HttpClient 的，缺一个服务端就挑刺
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("accept-encoding", "gzip")
            conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
            val jwe = prefs.jwe.trim()
            if (jwe.isNotEmpty()) conn.setRequestProperty("authorization", "Bearer $jwe")

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) throw IOException("HTTP $code")
            var raw = stream.use { it.readBytes() }
            // 服务端有时 gzip 有时明文
            if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                raw = GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            }
            val text = String(raw, Charsets.UTF_8)
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(160)}")
            if (text.isBlank()) throw IOException("空响应（这个端口不是 /room 的 vhost）")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ id 池
    /** 首次运行把 assets 里的种子拷到 filesDir，之后只用 filesDir 那份 */
    private fun ensureIdPool() {
        if (idPoolFile.exists() && idPoolFile.length() > 0) return
        runCatching {
            context.assets.open("room_ids.txt").bufferedReader().use { r ->
                idPoolFile.writeText(r.readText())
            }
        }
    }

    /** 读 id 池；顺手把缓存里的房间号并进去，保证不丢 */
    private fun idPool(): List<Long> {
        ensureIdPool()
        val set = LinkedHashSet<Long>()
        runCatching {
            if (idPoolFile.exists()) {
                idPoolFile.forEachLine { line ->
                    line.trim().toLongOrNull()?.takeIf { it > 0L }?.let { set.add(it) }
                }
            }
        }
        runCatching {
            if (cacheFile.exists()) {
                JSONObject(cacheFile.readText()).optJSONArray("rooms")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optLong("id", 0L)?.takeIf { it > 0L }?.let { set.add(it) }
                    }
                }
            }
        }
        return set.toList()
    }

    /** 把新学到的房间号并回池子（只增不减） */
    private fun mergeIdPool(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        runCatching {
            ensureIdPool()
            val set = LinkedHashSet<Long>()
            if (idPoolFile.exists()) {
                idPoolFile.forEachLine { line ->
                    line.trim().toLongOrNull()?.takeIf { it > 0L }?.let { set.add(it) }
                }
            }
            val before = set.size
            set.addAll(ids)
            if (set.size != before || before == 0) {
                idPoolFile.writeText(set.joinToString("\n") + "\n")
            }
        }
    }

    // ------------------------------------------------------------------ 刷新
    /**
     * 拉一遍全部已知房间的最新状态。
     *
     * 分片并发（每片 [batchSize] 个 id），只保留 `on_air=1`，按在线人数降序。
     * 全部失败会强制重新探测一次端口再试 —— 源 App 重启后端口号一定变。
     */
    suspend fun fetch(): Result<SourceData> = withContext(Dispatchers.IO) {
        runCatching {
            val ids = idPool()
            if (ids.isEmpty()) throw IOException("房间号池是空的（assets/room_ids.txt 没打包进来？）")

            var port = probePort(sample = ids.firstOrNull())

            // 关注的人大多不在大厅 id 池里（池子是按大厅表导出的），单独补一次
            val follows = pullFollows(port)
            followIds = follows.keys
            followNames = follows

            val targets = (ids + follows.keys).distinct()
            val rooms = runCatching { pullAll(port, targets) }.getOrElse { first ->
                // 端口大概率变了，重新探一次
                port = probePort(force = true, sample = ids.firstOrNull())
                runCatching { pullAll(port, targets) }.getOrElse { throw first }
            }

            if (rooms.isEmpty()) throw IOException("接口通了，但 1 个在播房间都没有（token 可能属于被封的号）")

            val sorted = rooms.sortedWith(
                compareByDescending<Room> { it.online }.thenByDescending { it.id }
            )
            val data = SourceData(
                sorted,
                SourceMeta(
                    rtmpDomain = prefs.rtmpDomain,
                    fetchedAt = System.currentTimeMillis(),
                    fromCache = false
                )
            )
            writeCache(sorted)
            data
        }
    }

    /**
     * 服务端关注。
     *
     * **`podcast_id` 就是房间号** —— 2026-10-29 实测：8 位关注拿去 `/room/get_list_by_ids`
     * 全部命中，返回的 `attachment_url` 与关注列表里的头像完全一致。所以拿到 podcast_id
     * 就等于拿到房间号，不用再做映射。
     *
     * 这个接口不分页：`page` / `pageIndex` / `offset` 传什么值都只回同一批，
     * 所以关注数超过返回条数时补不齐（当前账号 8 位，全拿到了）。
     * 失败不抛异常 —— 没配 JWE、号被封都不该影响主列表。
     */
    private fun pullFollows(port: Int): Map<Long, String> = runCatching {
        val obj = post(port, "/user_follow/my_follow", "{}", timeout = 6000)
        val arr = obj.optJSONArray("result") ?: return@runCatching emptyMap<Long, String>()
        val out = LinkedHashMap<Long, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("podcast_id", 0L)
            if (id > 0L) out[id] = o.optString("nickname")
        }
        out
    }.getOrDefault(emptyMap<Long, String>())

    /** 分片并发拉详情 */
    private suspend fun pullAll(port: Int, ids: List<Long>): List<Room> = coroutineScope {
        val chunks = ids.chunked(batchSize)
        val out = ArrayList<Room>(ids.size)
        val guard = java.util.concurrent.atomic.AtomicInteger(0)
        chunks.chunked(3).map { wave ->
            async(Dispatchers.IO) {
                val got = ArrayList<Room>()
                for (chunk in wave) {
                    val obj = runCatching {
                        post(
                            port,
                            "/room/get_list_by_ids",
                            JSONObject().put("ids", chunk.joinToString(",")).toString(),
                            timeout = 15000
                        )
                    }.getOrNull()
                    if (obj == null) {
                        guard.incrementAndGet()
                        continue
                    }
                    val arr = obj.optJSONArray("rooms") ?: continue
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { r -> toRoom(r)?.let { got.add(it) } }
                    }
                }
                got
            }
        }.awaitAll().forEach { out.addAll(it) }

        if (out.isEmpty() && guard.get() > 0) throw IOException("全部 $port 端口请求失败，源 App 可能已退出")
        // 顺手把这次见到的 id 并回池子（服务端偶尔会回比请求更多的房间）
        mergeIdPool(out.map { it.id })
        out
    }

    /** 只留正在播的，映射成 UI 用的精简模型 */
    private fun toRoom(r: JSONObject): Room? {
        val id = r.optLong("id", 0L)
        if (id <= 0L) return null
        if (r.optInt("on_air", 0) != 1) return null
        val menuId = r.optInt("menu_id", 0)
        return Room(
            id = id,
            name = r.optString("name").ifBlank { "房间 $id" },
            cat = menuName(menuId),
            online = r.optInt("player_count", 0),
            city = r.optString("city_show").ifBlank { r.optString("city") },
            // chess_id != 3 的基本是棋牌/游戏/推广房，默认过滤掉
            ad = r.optInt("chess_id", 0) != 3,
            coins = r.optInt("live_fee", 0),
            menuId = menuId,
            // 缓存文件里直接存完整 URL（键名 avatar）；接口响应里是相对路径
            avatar = r.optString("avatar").ifBlank {
                imageUrl(
                    r.optString("attachment_url")
                        .ifBlank { r.optString("attachment_db_url") }
                )
            }
        )
    }

    /**
     * 相对路径 → 图床 URL。
     *
     * `attachment_url` 长这样：`/static/upload/u/8200/9833/9/head/xxx.jpg`
     * 源 App 实际请求的是：`<图床>/images/256/u/8200/9833/9/head/xxx.jpg`
     * 也就是把 `/static/upload` 整段换成 `/images/<尺寸>`。
     */
    private fun imageUrl(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return ""
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        val base = prefs.imgBase.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val rest = p.removePrefix("/static/upload")
        return base + if (rest.startsWith("/")) rest else "/$rest"
    }

    /** 缓存原始精简 JSON，冷启动先铺屏 */
    private fun writeCache(rooms: List<Room>) {
        runCatching {
            val arr = JSONArray()
            for (r in rooms) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("menu_id", r.menuId)
                        .put("player_count", r.online)
                        .put("city_show", r.city)
                        .put("chess_id", if (r.ad) 0 else 3)
                        .put("live_fee", r.coins)
                        .put("avatar", r.avatar)
                        .put("on_air", 1)
                )
            }
            cacheFile.writeText(JSONObject().put("rooms", arr).toString())
        }
    }

    fun loadCache(): SourceData? = runCatching {
        if (!cacheFile.exists()) return@runCatching null
        val obj = JSONObject(cacheFile.readText())
        val arr = obj.optJSONArray("rooms") ?: return@runCatching null
        val rooms = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { toRoom(it)?.let { r -> rooms.add(r) } }
        }
        if (rooms.isEmpty()) null
        else SourceData(
            rooms,
            SourceMeta(rtmpDomain = prefs.rtmpDomain, fromCache = true)
        )
    }.getOrNull()

    // ------------------------------------------------------------------ 分类
    companion object {
        /**
         * `lobby_menu` 表里的分类（实测拉取，2026-10-29）。
         * 房间对象的 `menu_id` 就是这张表的 id —— 源 App 切分类就是按这个字段本地过滤。
         */
        private val MENU_NAMES = mapOf(
            1 to "关注",
            2 to "推荐",
            3 to "热门",
            4 to "附近",
            5 to "新人",
            8 to "游戏",
            101 to "星秀",
            102 to "女神",
            103 to "颜值",
            104 to "交友",
            105 to "音乐",
            108 to "舞蹈"
        )

        fun menuName(menuId: Int): String = MENU_NAMES[menuId] ?: "其他"
    }
}
