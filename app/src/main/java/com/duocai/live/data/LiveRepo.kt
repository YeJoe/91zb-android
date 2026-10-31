package com.tm.live.data

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
 * | `POST /user_follow/my_follow` | ✅ 关注的主播（podcast_id）。**分页，一页 20 条**，见 [pullFollows] |
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
     * 内置隧道（[com.tm.live.tunnel.KiwiSdk]）起来后由 [com.tm.live.AppContainer]
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
    /** 上次扫出 [wsVhosts] 时的 LISTEN 端口集合指纹（见 [cachedWsPorts]） */
    private var wsPortsSig: String = ""

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
     * 真正进房仍然由 [com.tm.live.data.LiveDanmaku] 开 WS 做。
     *
     * ## 缓存策略（2026-10-30 修，当天第二次改）
     *
     * 原实现「空结果也缓存 60 秒」是 404 的帮凶：隧道刚 init 时 listener 没 bind 稳，
     * 第一轮必然全失败，于是接下来整整 60 秒 `probeWsVhosts()` 都直接返回空表。
     * 中间改成「只有拿到结果才缓存 + 60 秒 TTL」，但**时间窗口本身就是错的判据**：
     * 端口表可能在 5 秒内就重排，也可能 10 分钟没动。现在换成 **LISTEN 端口集合指纹**——
     * 集合一模一样就说明端口没重排，结论继续有效；一变立刻重扫。
     *
     * @param force 忽略缓存强制重扫。设置页「诊断端口」用，见 [diagnoseWs]。
     */
    @Synchronized
    fun probeWsVhosts(force: Boolean = false): List<Int> {
        val ports = listenPorts()
        if (ports.isEmpty()) return emptyList()
        val sig = ports.joinToString(",")
        // 只有"上次确实扫出了东西"才允许走缓存；空结果不缓存
        if (!force && wsVhosts.isNotEmpty() && sig == wsPortsSig) return wsVhosts

        // 不再按上限截断候选：真 IM 端口可能落在临时端口段任意位置，
        // 截断会把正确答案切掉。按并发验，多试几个也就多一两秒。
        val result = verifyWsPorts(ports.sortedDescending())
        if (result.isNotEmpty()) wsPortsSig = sig
        return result
    }

    /**
     * 快路径：端口表没变时，上次 405 探测的命中可以直接拿出来用，**一个请求都不发**。
     *
     * 和 [probeWsVhosts] 的区别就是这里只读缓存、绝不探测。弹幕进房的第一轮走这个，
     * 避免了「为了试一个端口，先扫二十几个 LISTEN」。
     *
     * 判据用 LISTEN 集合指纹而不是时间：集合一样说明端口没重排，上次的结论就还成立。
     */
    @Synchronized
    fun cachedWsPorts(): List<Int> {
        if (wsVhosts.isEmpty()) return emptyList()
        val ports = listenPorts()
        if (ports.isEmpty()) return emptyList()
        return if (ports.joinToString(",") == wsPortsSig) wsVhosts else emptyList()
    }

    /**
     * 对指定端口做同一套判据，返回其中真正挂 `/websock` 的那些。
     *
     * 存在的理由：`/proc/net/tcp` 在新版 Android 上未必读得到**别的进程**的 listener，
     * 但内置隧道问出来的端口一定是我们自己进程的，直接验就行，不用绕路。
     *
     * 并发验（8 路）：14 个组串行各 1.2 秒要 17 秒，重连时根本等不起。
     *
     * ## 「一直 404」的两个真实成因（2026-10-30 修）
     *
     * 1. **失败缓存把正确端口毒化了**。原实现里「失败端口的时间戳」写入后 60 秒内不再重验，
     *    而 `wsVhosts` 只在 `hits.isNotEmpty()` 时才更新。于是：
     *    隧道刚 init 完、listener 还没 bind 稳 → 第一次验全部失败 → `wsVhosts` 恒空
     *    → 接下来 60 秒里哪怕隧道已经好了，`verifyWsPorts` 也只会从缓存里返回空表
     *    → 候选表退化成 `tunnel.port`（`/room` 组）→ join 必然 404。
     *    修法：**失败不再写缓存**（只缓存成功），失败的下轮立刻重试。
     *    代价是隧道没起来时每次重连多花 1.2 秒，但这比"端口明明对却判死"便宜得多。
     *
     * 2. **端口表变了、成功缓存没失效**。冷启动端口会重排（11689→34199→37679），
     *    旧的成功端口留在 `wsVhosts` 里，新一轮 `cached` 会把它当成"已验证"直接返回，
     *    而它此刻可能已经被别的服务占用。修法：每轮用**本轮的候选集**去交集，
     *    自然把过期的挤出去。
     */
    @Synchronized
    fun verifyWsPorts(candidates: Collection<Int>): List<Int> {
        val list = candidates.filter { it > 0 }.distinct()
        if (list.isEmpty()) return emptyList()

        // 只认本轮候选集里的成功记录，端口表换了自动失效
        val cached = list.filter { it in wsVhosts }
        // 只跳过**成功过**的端口；失败的下轮立刻重试（见上面注释 1）
        val fresh = list.filter { p -> p !in wsVhosts }

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

        // 成功集也要以本轮候选为界，避免无限增长
        wsVhosts = (hits + cached).distinct().filter { it in list }
        // 结果被本轮候选**收缩**过，不再是「全量 LISTEN 扫描的结论」，
        // 指纹作废，让下次 [probeWsVhosts] 老实重扫一遍，别拿半张表当全表用。
        wsPortsSig = ""
        return wsVhosts
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
     *
     * ## 超时为什么从 1200ms 提到 2500ms（2026-10-30）
     *
     * 隧道是「本地 listener → 网关 → 远端 nginx」，首包要走完整条链路。
     * 实测隧道刚 init 完的头几秒，同一个端口 1.2 秒连不回是常态；
     * 被判成 false 之后端口就进不了候选表，于是表现成「一直 404」。
     * 2500ms 仍远低于用户可感知的等待，但足够覆盖冷启动抖动。
     * 并发 8 路时最坏一轮约 2.5 秒，比"判错然后永远 404"划算。
     */
    private fun wsJoinReachable(port: Int): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:$port/websock/join").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
            // 带上 JWE 更接近真实请求：部分 vhost 的鉴权层会先看这个头再决定回 405 还是 403
            val jwe = prefs.jwe.trim()
            if (jwe.isNotEmpty()) conn.setRequestProperty("authorization", "Bearer $jwe")
            conn.outputStream.use {
                it.write("""{"room_id":1,"menu_id":1,"cp_type":3}""".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code == 405) return@runCatching true
            // 405 之外的 4xx 也可能是「路由在、但被鉴权先拦」，读 body 兜底
            if (code in 400..499) {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                // 这几句只可能来自 /websock 的鉴权层
                if (body.contains("bad token") || body.contains("method") && body.contains("not allowed")) {
                    return@runCatching true
                }
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

            // 关注的人大多不在大厅 id 池里（池子是按大厅表导出的），单独补一次。
            // 这里会翻页拿全量（一页 20 条）；**失败时返回 null，此时保持上一次的结果不动** ——
            // 之前把失败当成空集写回去，一次网络抖动就能让「关注」整片消失。
            val follows = pullFollows(port)
            if (follows != null) {
                followIds = follows.keys
                followNames = follows
            }
            val followSet = follows?.keys ?: followIds

            val targets = (ids + followSet).distinct()
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
     * 服务端关注 —— **全量**，会自动翻页。
     *
     * `podcast_id` 就是房间号 —— 2026-10-29 实测：拿 8 位关注去 `/room/get_list_by_ids`
     * 全部命中，返回的 `attachment_url` 与关注列表里的头像完全一致。所以拿到 podcast_id
     * 就等于拿到房间号，不用再做映射。
     *
     * ## 2026-10-31 修正：这个接口是分页的，一页固定 20 条
     *
     * 之前只发一次 `{}` 就当成了全集，结果是「关注列表和服务端不一样」的真正原因：
     *
     * ```
     * 账号 1422303235（真号）实测：
     *   {"page":1}  → {"count":299,"has_next":1,"result":[ …20 条… ]}
     *   {"page":2}  → 20 条        {"page":16} → 3 条       {"page":0} → 0 条
     * ```
     *
     * `result` 只是当前这一页；旧代码只取第一页，于是 App 里永远只有 20 位关注，
     * 而服务端实际有 303 位。注意 `count` **也不准**：报 299，翻完 16 页去重后是 303
     * （偏小 4），所以它只能当参考，绝不能拿来算页数。
     *
     * 几个必须按实测来的细节：
     *  - `page` **从 1 开始**，传 0 得到的是空页（不是第一页）；
     *  - `pageSize` 服务端不认，传 100 依然回 20 条；
     *  - `has_next` 恒为 1，**不可信**，唯一可靠的终止判据是「本页不足 20 条」。
     *
     * 所以：第 1 页不满 20 条就直接收工（绝大多数账号一发就够）；否则按
     * [FOLLOW_PAGE_WAVE] 页一波并发往后翻，遇到空页/不满页停。不靠 `count` 算页数 ——
     * 那个数在实测里和实际页数对不上（299 条却有 16 页）。
     *
     * @return `房间号 → 昵称`；**第 1 页就失败时返回 null**，调用方不要拿空集覆盖已有列表
     */
    private suspend fun pullFollows(port: Int): Map<Long, String>? = coroutineScope {
        val first = followPage(port, 1) ?: return@coroutineScope null
        val out = LinkedHashMap<Long, String>(first.ids)
        // 只有一页（多数账号）：不再多发请求
        if (first.ids.size < FOLLOW_PAGE_SIZE) return@coroutineScope out

        var page = 2
        while (page <= MAX_FOLLOW_PAGES) {
            val wave = (page until (page + FOLLOW_PAGE_WAVE).coerceAtMost(MAX_FOLLOW_PAGES + 1)).toList()
            val got = wave.map { p -> async(Dispatchers.IO) { followPage(port, p) } }.awaitAll()
            var stop = false
            for (fp in got) {
                // 中途某一页失败：已拿到的照用，别再往后翻（下一页大概率也失败）
                if (fp == null || fp.ids.isEmpty()) { stop = true; break }
                out.putAll(fp.ids)
                if (fp.ids.size < FOLLOW_PAGE_SIZE) { stop = true; break }
            }
            if (stop) break
            page += wave.size
        }
        out
    }

    /** 一页关注 `(服务端报的总数, 房间号→昵称)` */
    private class FollowPage(val count: Int, val ids: Map<Long, String>)

    /** 拉一页关注。page 从 1 开始；请求失败（抛异常/HTTP 错）返回 null，与「这一页是空的」区分开 */
    private fun followPage(port: Int, page: Int, timeout: Int = 8000): FollowPage? = runCatching {
        val obj = post(port, "/user_follow/my_follow", JSONObject().put("page", page).toString(), timeout)
        val ids = LinkedHashMap<Long, String>()
        val arr = obj.optJSONArray("result")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("podcast_id", 0L)
                if (id > 0L) ids[id] = o.optString("nickname")
            }
        }
        FollowPage(obj.optInt("count", ids.size), ids)
    }.getOrNull()

    /**
     * 拉一次服务端关注快照 `(房间号集合, 房间号→昵称)`。
     *
     * 供 UI 在关注/取关之后刷新界面用 —— 这是**唯一**的真相源：
     * 本地不再持久化关注关系（见 `AppContainer.toggleFollow` 的注释），
     * 所以每次写操作后都要重拉一次，界面才不会和源 App 里看到的不一致。
     *
     * @return 端口不可用 / 请求失败时返回 null，调用方保持原状即可
     */
    suspend fun pullFollowSnapshot(): Pair<Set<Long>, Map<Long, String>>? =
        withContext(Dispatchers.IO) {
            val port = runCatching { probePort(sample = idPool().firstOrNull()) }.getOrNull() ?: 0
            if (port <= 0) return@withContext null
            // null = 第 1 页就没打通（保持原状）；空 Map = 账号确实一位都没关注。
            // 以前这两件事都落成 emptyMap，再拿 `my_follow_count` 去分辨 —— 而那个接口
            // 无论有没有关注都回 `{"code":0,"data":0}`，等于永远判不出失败。
            val m = pullFollows(port) ?: return@withContext null
            m.keys to m
        }

    /**
     * 关注 / 取消关注（**尽力而为**）。
     *
     * ## 现状（2026-10-31 复查结论，别再来一遍）
     * 明文隧道里**没有** `/relationship/add|remove` 这条路由：17 个 listener 全回网关兜底的
     * `{"code":0}`（看着像成功），`my_follow` 条数一条没变。写接口只在 `/go/` 加密通道上，
     * 那条通道要另一套 go 侧 session，当前够不到。
     *
     * 所以这里**只试一次写、不等复查**：
     *  - post 到未知路径会让 Dart 单 isolate 服务端挂起，给 2.5s 短超时兜底；
     *  - 不再拉全量 `my_follow` 复查（真号 304 条要 4~5 秒，纯浪费，且当前架构下必失败）；
     *  - 网关对未知路径也回 `{"code":0}` 骗人，所以宁可如实返回 false，让 UI 标「仅本机」，
     *    也不要凭一个 code=0 谎报关注成功。
     *
     * 想真打通得从源 App 的加密通道取会话（Frida / 逆密钥派生），不是改这里。
     *
     * @param podcastId 房间号（与 `podcast_id` 同空间，服务端按 `followeeId` 收）
     * @param wantFollow true=关注 / false=取消关注
     * @return 服务端状态是否确实变成 [wantFollow]（当前几乎必然 false，调用方要能接受）
     */
    suspend fun syncFollow(podcastId: Long, wantFollow: Boolean): Boolean = withContext(Dispatchers.IO) {
        val port = runCatching { probePort(sample = idPool().firstOrNull()) }.getOrNull() ?: 0
        if (port <= 0) return@withContext false

        // 关注对象 = 主播的用户 id。podcast_id 与房间号同空间，服务端按 followeeId 收。
        val body = JSONObject()
            .put("userId", myUserId())            // 自己
            .put("followeeId", podcastId)         // 关注对象（= 房间号）
            .put("channelId", prefs.channelId)    // 渠道

        val path = if (wantFollow) "/relationship/add" else "/relationship/remove"
        // 明文隧道没有这条路由：试一次写，短超时兜底，不复查（避免 4~5 秒卡 UI）。
        runCatching { post(port, path, body.toString(), timeout = 2500) }
        // 无法确认写成功（网关兜底也回 code=0 骗人），如实返回 false。
        false
    }

    /** 当前登录用户 id（`MAINUSER_DATA.id`，缺省 0 = 让服务端按 token 认） */
    private fun myUserId(): Long = prefs.myUserId

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

    // ------------------------------------------------------------------ 弹幕诊断
    /** 诊断时单个端口的探测结果 */
    private data class WsProbeRow(
        val port: Int,
        val code: Int,
        val body: String,
        val hit: Boolean
    )

    /**
     * 逐个端口跑一遍 405 判据，返回可直接贴给用户看的明细。
     *
     * 存在的理由：判断「为什么 404」时最缺的不是结论，而是**每个端口到底回了什么**。
     * 原实现只有「试了 N 个端口」一句话，看不出是端口没拿到、还是拿到了但判据判错了。
     *
     * 副作用（有意为之）：**命中的端口会被记进候选表**。诊断本身已经把每个端口都验过一遍，
     * 不把这个结果留下来，用户还得再等一轮重连去重复同样的探测。
     *
     * @param ports 要诊断的端口；空则取优先端口 + 本机全部 LISTEN 端口
     * @param limit 最多验多少个，避免一次诊断拖太久
     */
    suspend fun diagnoseWs(ports: List<Int> = emptyList(), limit: Int = 32): String =
        withContext(Dispatchers.IO) {
            val raw = (if (ports.isEmpty()) {
                (preferPort.takeIf { it > 0 }?.let { listOf(it) } ?: emptyList()) +
                    listenPorts()
            } else ports).filter { it > 0 }.distinct()
            val candidates = raw.take(limit)

            if (candidates.isEmpty()) return@withContext "没有候选端口（隧道没起来？）"

            // 并发 8 路：串行扫 24 个、每个 2.5 秒上限，用户等不起
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, candidates.size))
            val rows = try {
                candidates
                    .map { p -> pool.submit(java.util.concurrent.Callable { probeJoin(p) }) }
                    .mapNotNull { f -> runCatching { f.get() }.getOrNull() }
                    .sortedBy { it.port }
            } finally {
                pool.shutdown()
            }

            val hits = rows.filter { it.hit }.map { it.port }
            if (hits.isNotEmpty()) {
                wsVhosts = (hits + wsVhosts).distinct()
                // 扫全了才算「全量结论」，指纹据此生效，进房时快路径能直接拿来用；
                // 被 limit 截断就作废，宁可下次重扫，也别拿半张表当全表。
                wsPortsSig = if (raw.size <= limit) listenPorts().joinToString(",") else ""
            }

            fun fmt(r: WsProbeRow): String {
                val mark = when {
                    r.code == 405 -> "✅ IM vhost"
                    r.code == 403 && r.body.contains("bad token") -> "✅ IM vhost（403 兜底）"
                    r.code == 403 -> "别的 vhost 鉴权层"
                    r.code == 404 -> "不挂 /websock"
                    r.code == -1 -> "超时/连不上"
                    else -> "未判定"
                }
                val brief = briefBody(r.body)
                return "  :${r.port}  HTTP ${r.code}  $mark" + if (brief.isNotEmpty()) "  $brief" else ""
            }

            buildString {
                appendLine("弹幕端口诊断（判据：明文 POST /websock/join 回 405）")
                // 405 与 token 无关（那是 nginx 的方法层），所以诊断只能证明「端口对」。
                // 真正决定能不能进房的是会话 token —— 单独报一行，避免又把两件事混在一起。
                val tk = prefs.appToken.trim()
                appendLine(
                    "会话 token：" + (if (tk.isEmpty()) "空 → 就算端口全对，join 也会 403 bad token"
                    else "${tk.take(6)}…${tk.takeLast(4)}（${tk.length} 位）")
                )
                appendLine("共 ${rows.size} 个端口，命中 ${hits.size} 个" +
                    (if (hits.isNotEmpty()) "：" + hits.joinToString(" ") { ":$it" } else ""))
                if (hits.isNotEmpty()) {
                    appendLine()
                    rows.filter { it.hit }.forEach { appendLine(fmt(it)) }
                    appendLine()
                    appendLine("其余（都不是 IM vhost）：")
                    rows.filterNot { it.hit }.forEach { appendLine(fmt(it)) }
                    appendLine()
                    appendLine("命中端口已记进候选表 → 点上面的「重连弹幕」直接用它，不用再扫")
                    if (prefs.appToken.trim().isEmpty()) {
                        appendLine()
                        appendLine("⚠️ 但会话 token 是空的：join 一定回 403 bad token。")
                        appendLine("   把导出工具的【会话 Token】那一串 32 位 hex 填进上面的框，再点「保存并重连」。")
                    }
                } else {
                    appendLine()
                    rows.forEach { appendLine(fmt(it)) }
                    appendLine()
                    appendLine("一个都没命中：多半是隧道还没起稳，等两三秒再诊断一次；")
                    appendLine("如果一直如此，看「端口」那行的 im 是否为 0（为 0 说明 SDK 没起来）")
                }
            }.trimEnd()
        }

    /** 诊断用：对一个端口打一发明文 join，返回带判定的结果 */
    private fun probeJoin(port: Int): WsProbeRow {
        val pair = runCatching {
            val conn = (URL("http://127.0.0.1:$port/websock/join")
                .openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
                val jwe = prefs.jwe.trim()
                if (jwe.isNotEmpty()) conn.setRequestProperty("authorization", "Bearer $jwe")
                conn.outputStream.use {
                    it.write("""{"room_id":1,"menu_id":1,"cp_type":3}""".toByteArray())
                }
                val c = conn.responseCode
                val b = runCatching {
                    (if (c in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                c to b
            } finally {
                conn.disconnect()
            }
        }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

        val (code, body) = pair
        val hit = code == 405 ||
            (code in 400..499 && (body.contains("bad token") ||
                body.contains("method") && body.contains("not allowed")))
        return WsProbeRow(port, code, body, hit)
    }

    /**
     * 诊断输出里的 body 只留有用的一小段。
     *
     * HTML 错误页（`<html><head><title>404 Not Found`）占满屏却没有信息量 —— HTTP 码已经说明
     * 一切；只有 JSON 才值得展示，`{"code":-10000,"msg":"bad token…"}` 这种才看得出是鉴权层。
     */
    private fun briefBody(body: String): String {
        val b = body.trim().replace(Regex("\\s+"), " ")
        if (b.isEmpty()) return ""
        return when {
            b.startsWith("{") || b.startsWith("[") -> b.take(80)
            b.startsWith("<") || b.contains("<title>") -> ""      // HTML 错误页，噪声
            else -> b.take(40)
        }
    }

    // ------------------------------------------------------------------ 分类
    companion object {
        /**
         * `live_in` 软筛的下限。
         *
         * 筛完剩不到这么多，说明要么此刻全场确实没人开播、要么服务端字段口径变了，
         * 这时候整批放行（让用户还能点进去试），不能给一张空列表。
         */
        const val MIN_KEPT_ROOMS = 8

        /**
         * `my_follow` 每页固定 20 条（服务端不认 `pageSize`，实测传 100 也只回 20）。
         * 用它判断「这一页是不是最后一页」—— 只有不满 20 条才是真的完了。
         */
        const val FOLLOW_PAGE_SIZE = 20

        /** 翻页时一波并发几页。10 页 ≈ 1 个来回，比逐页翻快得多 */
        const val FOLLOW_PAGE_WAVE = 5

        /** 翻页上限：50 页 = 1000 位关注，够用且能挡住被别人写坏的数据 */
        const val MAX_FOLLOW_PAGES = 50

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
