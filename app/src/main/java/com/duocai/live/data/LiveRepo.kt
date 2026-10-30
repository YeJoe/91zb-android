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
 *    `on_air / live_in / player_count / menu_id / city_show`，只留 `on_air=1` 的真人房，
 *    再用 `live_in` 软筛一道（筛完一个不剩就整批放行）。`on_air` 常年为 1、
 *    `player_count` 下播后不清零，都不可信；`live_in` 是唯一会翻转的那一个，当第一道筛用（免费）。
 *    剩下的「此刻真的拉得到流吗」交给 [probePlayable]，但它**只探可见窗口**、
 *    结果缓存 20 分钟（见 AppContainer.ensureProbed），不再全量探。
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

    /** 本机所有 LISTEN 端口（弹幕模块找 IM vhost 用，和列表探测共用一份扫法） */
    fun localListenPorts(): List<Int> = listenPorts()

    // ------------------------------------------------------ 弹幕 vhost 筛选
    /** [probeWsVhosts] 的结果缓存：一次连不上会反复重连，不能每次都把端口扫一遍 */
    private var wsVhosts: List<Int> = emptyList()
    private var wsVhostsAt = 0L

    /**
     * 找出真的挂 `/websock` 的端口。
     *
     * **判据改过三次，前两次都错在哪，记清楚免得再走回头路**：
     *  1. 只看「WS 握手能不能 101」—— 错。升级发生在隧道网关层，跟 vhost 无关，
     *     随便一个端口都能 101，但随后的 `/websock/join` 会被那个 nginx 404。
     *  2. 改成「明文 POST 回 200..299 且 body 是 JSON」—— 也错，而且是致命的：
     *     实测真端口回的是 **405**（业务 nginx 只认 WS 升级，明文 POST 在 method 层就被拒），
     *     这句 `responseCode !in 200..299` 把真端口直接判死，命中列表**永远为空**。
     *     于是候选只剩 `tunnel.port`（/room 组），表现就是「试了 N 个端口全 404」。
     *  3. 现在只看 **405**。2026-10-30 实测区分度干净：
     *
     *     | 端口            | 明文 POST /websock/join      | 判定 |
     *     |-----------------|------------------------------|------|
     *     | 37679（真 kiwi_im）| 405 + `{"code":-10000,...}` | 命中 |
     *     | 44775 / 14369   | 403 + `{"msg":"设备登陆中"}`  | 排除 |
     *     | 37783           | 403 + `{"msg":"用户不存在..."}`| 排除 |
     *
     *     403 那几个也是 JSON，看着很像命中，但那是别的业务 vhost 的鉴权层回的，
     *     路由根本不认 `/websock`。所以不能验 body，只验状态码。
     *
     * 明文探测 1.5 秒封顶、不需要 token / JWE，比逐个开 WS 便宜得多。
     * 真正进房仍然由 [com.duocai.live.data.LiveDanmaku] 开 WS 做。
     */
    @Synchronized
    fun probeWsVhosts(): List<Int> {
        val now = System.currentTimeMillis()
        if (now - wsVhostsAt < 60_000L) return wsVhosts
        wsVhostsAt = now
        // 隧道 listener 都落在临时端口段，从大往小扫更容易先命中
        // 走并发版：串行扫 24 个端口要 29 秒，重连根本等不起
        verifyWsPorts(listenPorts().sortedDescending().take(MAX_PROBE_PORTS))
        return wsVhosts
    }

    /** 端口 -> 上次验它的时间。验失败也要记，否则每轮重连都把失败端口再试一遍 */
    private val wsTriedAt = HashMap<Int, Long>()

    /**
     * 对指定端口做同一套判据，返回其中真正挂 `/websock` 的那些。
     *
     * 存在的理由：`/proc/net/tcp` 在新版 Android 上未必读得到**别的进程**的 listener，
     * 但内置隧道问出来的端口一定是我们自己进程的，直接验就行，不用绕路。
     *
     * 并发验（8 路）：14 个组串行各 1.2 秒要 17 秒，重连时根本等不起。
     * 60 秒内验过的端口（无论成没成）都不再重验，所以一轮重连只花新端口的钱。
     */
    @Synchronized
    fun verifyWsPorts(candidates: Collection<Int>): List<Int> {
        val now = System.currentTimeMillis()
        val list = candidates.filter { it > 0 }.distinct()
        if (list.isEmpty()) return emptyList()
        val cached = list.filter { it in wsVhosts }
        val fresh = list.filter { now - (wsTriedAt[it] ?: 0L) > 60_000L }
        if (fresh.isEmpty()) return cached

        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, fresh.size))
        val hits = try {
            fresh.map { p ->
                pool.submit(java.util.concurrent.Callable { p to wsJoinReachable(p) })
            }.mapNotNull { f ->
                runCatching { f.get() }.getOrNull()
            }.filter { it.second }.map { it.first }
        } catch (e: Exception) {
            emptyList()
        } finally {
            pool.shutdown()
        }
        fresh.forEach { wsTriedAt[it] = now }
        if (hits.isNotEmpty()) {
            wsVhosts = (hits + cached).distinct()
        }
        // 端口表整体换了的时候顺便清一下，别让这张表无限长
        if (wsTriedAt.size > 128) {
            wsTriedAt.entries.removeAll { now - it.value > 300_000L }
        }
        return (hits + cached).distinct()
    }

    /**
     * 明文 POST 一次 `/websock/join`：**405 是主判据**。
     *
     * 405 = "这个路由存在，但只接受 WS 升级" —— 正是 `/websock/…` 在业务 nginx 上的指纹。
     * 403 的端口（`设备登陆中` / `用户不存在或超时`）是别的 vhost 的鉴权层，路由不认
     * `/websock`，必须排除。
     *
     * 保险丝：万一哪天网关把"方法不允许"改成 403，只要 body 里是 token 报错
     * （`bad token, please login!`）也算命中 —— 那句只可能来自 `/websock` 的鉴权。
     */
    private fun wsJoinReachable(port: Int): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:$port/websock/join").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
            conn.outputStream.use {
                it.write("""{"room_id":1,"menu_id":1,"cp_type":3}""".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code == 405) return@runCatching true
            if (code == 403) {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                return@runCatching body.contains("bad token")
            }
            false
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

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
        // preferPort 是**权威**：内置隧道起来后它指向我们进程里那个 listener，
        // 而缓存里可能还是上一次扫到的源 App 端口（那个会随源 App 退出而失效）。
        // 所以只要两者不一致，缓存立刻作废，重新探。
        val stale = preferPort > 0 && cachedPort != 0 && cachedPort != preferPort
        if (!force && !stale && cachedPort != 0 && now - portCheckedAt < 60_000) return cachedPort

        val test = sample ?: idPool().firstOrNull()
        if (test == null) throw IOException("房间号池是空的")

        val preferred = prefs.tunnelPort
        val candidates = buildList {
            // 内置隧道指定的端口排第一：它一定挂业务 vhost，命中即用
            if (preferPort > 0) add(preferPort)
            if (preferred > 0) add(preferred)
            // 扫 /proc/net/tcp 去"借"别的进程的 listener 是**兜底的后门**，
            // 默认关掉：内置隧道通了就不该再蹭源 App 的端口（而且源 App 一退就失效）。
            // 只有设置页显式打开「使用源 App 后台隧道」才走这条路。
            if (prefs.sourceTunnel) addAll(listenPorts())
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
        throw IOException("隧道端口不可用：内置隧道没起来，或没开「源 App 后台隧道」兜底（已试 ${candidates.size} 个端口）")
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
     * 分片并发（每片 [batchSize] 个 id，再按 3 片一波滚动），只保留 `on_air=1` 的真人房，
     * 再按 `live_in` 软筛一道，最后按在线人数降序（**不过滤人数**，原因见下面的注释）。
     * 全部失败会强制重新探测一次端口再试 —— 源 App 重启后端口号一定变。
     *
     * @param limit 只拉 id 池的前 N 个。首屏用（池子近 2000 个，全量要 2~4 秒），
     *              先拉 500 个让画面立刻出来，剩下的交给后台那一趟补全。
     */
    suspend fun fetch(limit: Int = Int.MAX_VALUE): Result<SourceData> = withContext(Dispatchers.IO) {
        runCatching {
            val all = idPool()
            if (all.isEmpty()) throw IOException("房间号池是空的（assets/room_ids.txt 没打包进来？）")
            val ids = if (all.size > limit) all.take(limit) else all

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

            // 第一道筛（免费）：`live_in==0` 是服务端明确说「此刻没推流」，实测约占三分之一。
            // 挡在这里，后面的拉流探活就少发三分之一的请求。
            //
            // 但这是**软筛**：筛完一个不剩（比如凌晨全场都没开播、或者服务端把字段改回 0）
            // 就整批放行 —— 宁可多显示几张能点进去试的卡片，也不能给一张空列表。
            // 这是上一版「刷新后 0 房间」的直接教训。
            val onAir = rooms.filter { it.liveIn }
            val kept = if (onAir.size >= MIN_KEPT_ROOMS) onAir else rooms

            // 这里**不再**用 player_count 过滤。实测它是残留值：房间下播后人数不清零，
            // 按它筛既挡不住已下播的房、又可能把列表筛空。真正的判据只有「能不能拉到流」，
            // 由 [probePlayable] 探可见窗口（见 AppContainer.ensureProbed）。
            val sorted = kept.sortedWith(
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
            // 只拉了前 N 个（首屏快速批）就别覆盖缓存，否则冷启动能铺屏的房间会越刷越少
            if (ids.size == all.size) writeCache(sorted)
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

    /**
     * 批量探活：`http://<域名>/live/<房间号>.flv` 能不能吐出 FLV 头。
     *
     * 这也是**唯一可靠**的「房间此刻真的在播」判据。接口里的 `on_air` 恒为 1、
     * `player_count` 下播后不清零，`live_in` 只是软筛（`get_list_by_ids` 的 600 条样本里
     * 仅 5 条为 0），靠它们筛出来的列表里有大量点进去就报错的房 —— 用户看到的就是
     * 「90% 不能播放」。
     *
     * 做法：带 `Range: bytes=0-15` 只读响应头的十几个字节，攒够 `F L V` 三个字母就算通。
     * 下播的房间一律回 `403`（实测 NgxFence 挡的，且是**房间级**：换子域名、绕代理、
     * 设备内手打 HTTP 结果都一样），拿不到头就判为不可播。
     *
     * **调用方一次只传可见窗口**（AppContainer.ensureProbed 会限批），别把整池 1972 间
     * 丢进来 —— 24 路并发跑全量要 40 秒满功率射频，是上一版「进列表先卡一下」的元凶。
     *
     * @return 可播房间的 id 集合（探测本身出错时返回空集，调用方要有安全阀）
     */
    suspend fun probePlayable(
        list: List<Room>,
        domain: String,
        concurrency: Int = 24
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (list.isEmpty() || domain.isBlank()) return@withContext emptySet()
        val ok = java.util.Collections.synchronizedSet(HashSet<Long>())
        list.chunked(concurrency.coerceAtLeast(1)).forEach { chunk ->
            coroutineScope {
                chunk.map { r ->
                    async {
                        // 用 Room 自己的地址构造，保证和播放器实际拉的是同一个 URL
                        val url = r.streamUrls(domain, StreamMode.FLV).firstOrNull() ?: return@async
                        if (flvAlive(url)) ok.add(r.id)
                    }
                }.awaitAll()
            }
        }
        ok.toSet()
    }

    /** 单个 FLV 地址探活：只读前 3 个字节，是 `FLV` 就算在播 */
    private fun flvAlive(url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("Range", "bytes=0-15")
            conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
            if (conn.responseCode !in 200..299) return@runCatching false
            conn.inputStream.use { ins ->
                val b = ByteArray(3)
                var n = 0
                while (n < 3) {
                    val r = ins.read(b, n, 3 - n)
                    if (r <= 0) break
                    n += r
                }
                n == 3 && b[0] == 0x46.toByte() && b[1] == 0x4C.toByte() && b[2] == 0x56.toByte()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** 只留正在播的、真人房，映射成 UI 用的精简模型 */
    private fun toRoom(r: JSONObject): Room? {
        val id = r.optLong("id", 0L)
        if (id <= 0L) return null
        if (r.optInt("on_air", 0) != 1) return null

        // `live_in` = 服务端「此刻是否在推流」，是唯一会翻转的状态位。
        // 这里只记录、**不丢房间** —— 丢不丢由 fetch 决定（见那里的软筛：筛完一个不剩就不筛）。
        // 字段缺失按 1 放行：不能把「字段缺失」当「下播」，否则服务端改一次字段名列表就空了。
        val liveIn = r.optInt("live_in", 1) != 0

        val menuId = r.optInt("menu_id", 0)
        val name = r.optString("name").ifBlank { "房间 $id" }

        // ---- 非真人房硬过滤（唯一入口：缓存、列表、关注、播放页都从这里出去）----
        // 判据见 [NonHuman]：分区黑名单（menu_id 8 游戏区）+ 房名话术黑名单。
        // 这里**故意不看 chess_id** —— 它对真人房是随机值（实测 100+ 个真人被旧规则误杀），
        // 对赌博房又常常等于 3（快三/牛牛/倍投那批就是这么混进分类的）。
        if (!NonHuman.isHumanRoom(menuId, name)) return null

        return Room(
            id = id,
            name = name,
            cat = menuName(menuId),
            online = r.optInt("player_count", 0),
            city = r.optString("city_show").ifBlank { r.optString("city") },
            // 走到这里的都是真人房，ad 恒为 false；字段留着是为了兼容旧缓存
            ad = false,
            coins = r.optInt("live_fee", 0),
            menuId = menuId,
            // 缓存文件里直接存完整 URL（键名 avatar）；接口响应里是相对路径。
            // 统一过一遍 ImageUrls.plain：接口/旧缓存给的可能是 /images/<宽>/ 加密形态，
            // 那种 URL 下回来的是 `<mode iv="…">3</mode>` 密文，Coil 解不成图只会画成透明。
            avatar = ImageUrls.plain(
                r.optString("avatar").ifBlank {
                    imageUrl(
                        r.optString("attachment_url")
                            .ifBlank { r.optString("attachment_db_url") }
                    )
                }
            ),
            liveIn = liveIn
        )
    }

    /**
     * 相对路径 → 图床 URL。
     *
     * `attachment_url` 长这样：`/static/upload/u/8200/9833/9/head/xxx.jpg`
     *
     * 图床根有两种写法，都能用：
     *   · `<cdn>/images/480`   —— 源 App 的写法，但要的是**加密缩略图**，Coil 解不出
     *   · `<cdn>/static/upload` —— 明文原图
     * 这里只做「去前缀 + 拼根」，最终的加密→明文换算交给 [ImageUrls.plain]，
     * 所以历史缓存里存着的 `/images/<宽>/…` 旧地址也会被一并纠正。
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
                        // chess_id 已不参与过滤（见 NonHuman 注释），写 3 只是为了兼容老版本读缓存
                        .put("chess_id", 3)
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
         * 弹幕端口粗筛最多扫多少个监听端口。
         * 真机上报「试了 3 个端口全 404」说明一条都没筛出来，所以放宽到 64 ——
         * 本机 LISTEN 端口通常只有十几个，多扫一些代价也就是几十毫秒。
         */
        private const val MAX_PROBE_PORTS = 64

        /**
         * `live_in` 软筛的下限。
         *
         * 筛完剩不到这么多，说明要么此刻全场确实没人开播、要么服务端字段口径变了，
         * 这时候整批放行（让用户还能点进去试），不能给一张空列表。
         */
        const val MIN_KEPT_ROOMS = 8

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
