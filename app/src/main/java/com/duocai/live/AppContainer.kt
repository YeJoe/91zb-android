package com.duocai.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duocai.live.data.DanmakuStore
import com.duocai.live.data.FollowStore
import com.duocai.live.data.LiveRepo
import com.duocai.live.data.Prefs
import com.duocai.live.data.Room
import com.duocai.live.data.RoomRepo
import com.duocai.live.data.SourceData
import com.duocai.live.data.SourceMeta
import com.duocai.live.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Tab(val label: String) {
    HOME("首页"), FOLLOW("关注"), SETTINGS("设置")
}

/**
 * 首页的一级 tab —— 与源 App 大厅顶部那排一致。
 *
 * 注意：这四个**不是房间的分类字段**。实测 `/room/get_list_by_ids` 返回的 `menu_id`
 * 只有 8 / 101~105 / 108（游戏、星秀、女神、颜值、交友、音乐、舞蹈），从来没有 1/2/3/4。
 * 也就是说 1关注 2推荐 3热门 4附近 是**列表入口**，不是房间属性：
 *
 * - 关注：源 App 按账号的关注关系筛 → 我们按本地收藏筛
 * - 推荐：服务端按算法下发 → 没有列表接口，只能本地模拟（人气分档 + 房号哈希打散）
 * - 热门：按人气降序 → 本地排序即可，口径一致
 * - 附近：服务端按定位下发 → 拿不到坐标，降级为「同城」（按 `city_show` 筛）
 */
enum class Feed(val label: String) {
    FOLLOW("关注"), RECOMMEND("推荐"), HOT("热门"), NEARBY("附近")
}

/**
 * 手写的依赖容器（不用 Hilt：省一个注解处理器、省 ~800KB、也省一堆 KSP 版本对齐）。
 *
 * 顺便承担了"进程内状态"的职责 —— 这些状态挂在 Application 上，
 * 横竖切换、Activity 重建都不会丢，方案里那堆 ViewModel 就一起省了。
 */
class AppContainer(context: Context) {

    val context: Context = context.applicationContext

    val prefs = Prefs(this.context)
    val repo = RoomRepo(this.context, prefs)
    /** 实时数据源：直连隧道端口。见 LiveRepo 头部注释。 */
    val live = LiveRepo(this.context, prefs)
    val follows = FollowStore(prefs)

    /**
     * 内置隧道：在自家进程里跑源 App 的云层 SDK（libkiwi.so + com.kiwi.sdk.*）。
     *
     * 通了就完全不需要源 App 常驻；没通（appKey 失效 / so 加载失败 / 设备 abi 不支持）
     * 会自动退回老行为 —— 扫本机监听端口去"借"源 App 起好的隧道。
     */
    val tunnel = com.duocai.live.tunnel.Tunnel(this.context, prefs)

    /**
     * 弹幕。端口候选按「越可能是 IM vhost 越靠前」排：
     *
     *  1. [com.duocai.live.tunnel.Tunnel.imPort] —— `kiwi_im` 组，**唯一**能跑
     *     `/websock/*` 的 vhost（源 App 的 `im_url = http://kiwi_im/tunnel/open?z=1`）；
     *  2. [com.duocai.live.tunnel.Tunnel.port] —— `/room/*` 那个组，留着兜底；
     *  3. `live.lastPort` —— 没起内置隧道（源 App 模式）时扫到的端口。
     *
     * 以前这里直接给列表用的那个端口，WS 能握手但 `/websock/join` 被 404，
     * 于是永远收不到推送、被垫场样本顶上 —— 就是"每个房间弹幕都一模一样"的原因。
     */
    val danmaku = DanmakuStore(prefs) {
        listOf(tunnel.imPort, tunnel.port, live.lastPort)
            .filter { it > 0 }
            .distinct()
    }

    val player = PlayerHolder(this.context)

    /** 进程级作用域，承载自动刷新循环（App 活着就一直在） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var refreshStarted = false
    /** 同一时刻只允许一轮刷新在跑：手动刷新排队，后台轮询看 [loading] 跳过 */
    private val reloadLock = Mutex()

    val versionName: String = runCatching {
        this.context.packageManager
            .getPackageInfo(this.context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---- 数据 ----
    var rooms by mutableStateOf<List<Room>>(emptyList())
    var meta by mutableStateOf(SourceMeta())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedOnce by mutableStateOf(false)
    /** 上一次刷新是否真的连上了隧道端口（源 App 活着 = true） */
    var tunnelOk by mutableStateOf(false)
    /** 连上时用的端口，0 = 没连上。设置页显示「隧道在线 :12345」 */
    var tunnelPort by mutableStateOf(0)
    /** 手动刷新的结果提示，UI 消费一次后清掉 */
    var toast by mutableStateOf<String?>(null)

    // ---- 导航 ----
    var tab by mutableStateOf(Tab.HOME)
    /** 首页一级 tab。放在容器里而不是 HomeScreen 局部，切到别的页再回来才不会重置 */
    var feed by mutableStateOf(Feed.RECOMMEND)
    /** 当前播放页的房间序列（列表可能被分类/搜索筛过，翻页要跟着筛过的走） */
    var playlist by mutableStateOf<List<Room>>(emptyList())
    var openIndex by mutableStateOf(-1)

    /**
     * 是否正处于画中画（PIP）。原来这状态只活在 MainActivity 里，播放页靠
     * `host?.inPip` 读；但 RootScreen 也要用它来决断「系统返回键要不要拦截」
     * （小窗里返回键由系统接管，不能拦），所以提到容器里做唯一真相源，播放页直接读
     * `c.inPip`。MainActivity 在 `onPictureInPictureModeChanged` 里同步它。
     */
    var inPip by mutableStateOf(false)

    // ---- 关注（ids 是快照，供 Compose 观察）----
    var followedIds by mutableStateOf<Set<Long>>(emptySet())
    /** 源 App 账号在服务端关注的人（`podcast_id` 就是房间号）。实时刷新时更新。 */
    var serverFollowIds by mutableStateOf<Set<Long>>(emptySet())
    var serverFollowNames by mutableStateOf<Map<Long, String>>(emptyMap())

    /** 本地收藏 ∪ 服务端关注 —— 「关注」页和首页关注 tab 都用这一个口径 */
    fun followIdSet(): Set<Long> = followedIds + serverFollowIds

    // ---- 设置里会被界面直接读的几项，做成可观察 ----
    // 原先这里还有个 showAd（「显示推广位」）。开关连同 Prefs 字段一起删了：
    // 博彩/棋牌房在数据入口就被 NonHuman 过滤，没有"显示"这个选项。

    private var themeState by mutableStateOf(prefs.themeMode)
    var themeMode: Int
        get() = themeState
        set(v) {
            themeState = v
            prefs.themeMode = v
        }

    private var chatOnlyState by mutableStateOf(prefs.chatOnly)
    var chatOnly: Boolean
        get() = chatOnlyState
        set(v) {
            chatOnlyState = v
            prefs.chatOnly = v
        }

    /**
     * 下面这几项以前是「UI 直接读 prefs.xxx」—— 那是个普通 getter，
     * 写进 SharedPreferences 不会触发重组，所以点了开关/选项画面纹丝不动，
     * 得等别的原因引起重组才"突然生效"。现在统一挂到 mutableStateOf 上。
     */
    private var streamModeState by mutableStateOf(prefs.streamMode)
    /** 0 自动 / 1 只 FLV / 2 只 RTMP */
    var streamMode: Int
        get() = streamModeState
        set(v) {
            streamModeState = v
            prefs.streamMode = v
        }

    private var scaleModeState by mutableStateOf(prefs.scaleMode)
    /** 0 适应 / 2 拉伸（旧值 1=裁剪 已在 Prefs 里归一到 0） */
    var scaleMode: Int
        get() = scaleModeState
        set(v) {
            scaleModeState = v
            prefs.scaleMode = v
        }

    private var bufferMsState by mutableStateOf(prefs.bufferMs)
    var bufferMs: Int
        get() = bufferMsState
        set(v) {
            bufferMsState = v
            prefs.bufferMs = v
            player.setBuffer(v)
        }

    private var autoPlayState by mutableStateOf(prefs.autoPlay)
    var autoPlay: Boolean
        get() = autoPlayState
        set(v) {
            autoPlayState = v
            prefs.autoPlay = v
        }

    private var autoPipState by mutableStateOf(prefs.autoPip)
    /** 播放页按 Home 自动进画中画 */
    var autoPip: Boolean
        get() = autoPipState
        set(v) {
            autoPipState = v
            prefs.autoPip = v
        }

    private var refreshSecState by mutableStateOf(prefs.refreshSec)
    var refreshSec: Int
        get() = refreshSecState
        set(v) {
            refreshSecState = v
            prefs.refreshSec = v
        }

    private var nearbyCityState by mutableStateOf(prefs.nearbyCity)
    /** 「附近」页选中的城市；空 = 自动取房间最多的那个 */
    var nearbyCity: String
        get() = nearbyCityState
        set(v) {
            nearbyCityState = v
            prefs.nearbyCity = v
        }

    private var gridColsState by mutableStateOf(prefs.gridCols)
    /** 首页瀑布流列数 2/3（3 = 紧凑，一屏更多房间） */
    var gridCols: Int
        get() = gridColsState
        set(v) {
            val n = v.coerceIn(2, 3)
            gridColsState = n
            prefs.gridCols = n
        }

    /** 允不允许去"借"源 App 后台跑着的隧道（默认关，见 [Prefs.sourceTunnel]） */
    private var sourceTunnelState by mutableStateOf(prefs.sourceTunnel)
    var sourceTunnel: Boolean
        get() = sourceTunnelState
        set(v) {
            sourceTunnelState = v
            prefs.sourceTunnel = v
        }

    /** 封面图的 HTTP 代理（host:port，空 = 直连） */
    private var imgProxyState by mutableStateOf(prefs.imgProxy)
    var imgProxy: String
        get() = imgProxyState
        set(v) {
            imgProxyState = v
            prefs.imgProxy = v
            // 立刻重建 Coil 的 loader，改完不用重启
            runCatching {
                (context.applicationContext as? com.duocai.live.App)?.reloadImageLoader()
            }
        }

    /** 实时模式开关也放到容器里，避免和设置页的局部 state 打架 */
    private var liveModeState by mutableStateOf(prefs.liveMode)
    var liveMode: Boolean
        get() = liveModeState
        set(v) {
            liveModeState = v
            prefs.liveMode = v
        }

    /** 内置隧道：启动时自动拉起（开关同样要可观察，否则点了不重绘） */
    private var kiwiAutoState by mutableStateOf(prefs.kiwiAuto)
    var kiwiAuto: Boolean
        get() = kiwiAutoState
        set(v) {
            kiwiAutoState = v
            prefs.kiwiAuto = v
        }

    fun openPlayer(list: List<Room>, index: Int) {
        if (list.isEmpty()) return
        playlist = list
        openIndex = index.coerceIn(0, list.size - 1)
    }

    fun closePlayer() {
        openIndex = -1
        player.pause()
    }

    fun toggleFollow(room: Room) {
        follows.toggle(room)
        followedIds = follows.ids()
    }

    fun unfollow(id: Long) {
        follows.remove(id)
        followedIds = follows.ids()
    }

    fun followedList(): List<Room> {
        val byId = rooms.associateBy { it.id }
        return follows.all().mapNotNull { byId[it.id] }
    }

    /** 冷启动：先出缓存，再拉网络 */
    suspend fun bootstrap() {
        if (loadedOnce) return
        loadedOnce = true
        followedIds = follows.ids()
        (live.loadCache() ?: repo.loadCache())?.let { applyData(it, fromCache = true) }

        // 内置隧道单独起一条协程：握手要几秒，不能挡住列表首屏。
        // 它先在后台跑，成功后立刻把手上的端口交给 LiveRepo 并强制刷一轮。
        //
        // 架构门禁放在这里而不是只放 Tunnel 里：x86 设备上一旦进到 dlopen，
        // 加固库的 JNI_OnLoad 会 abort 整个进程，连 catch 的机会都没有。
        if (kiwiAuto && prefs.kiwiAppKey.isNotBlank() &&
            com.duocai.live.tunnel.KiwiSdk.abiSupported()
        ) {
            scope.launch {
                if (tunnel.start()) {
                    live.preferPort = tunnel.port
                    tunnelPort = tunnel.port
                    tunnelOk = true
                    reload(force = true)
                }
            }
        }

        reload()
        startAutoRefresh()
    }

    /**
     * 自动刷新循环：仅当 refreshSec>0 时生效。实时模式下让它"名副其实"。
     * 进程级协程，只在 bootstrap 后启动一次；循环里动态读 prefs.refreshSec，
     * 用户改了间隔下次生效，改成 0 就自然停。
     */
    private fun startAutoRefresh() {
        if (refreshStarted) return
        refreshStarted = true
        scope.launch {
            while (true) {
                val sec = refreshSec
                if (sec <= 0) {
                    delay(2000)
                    continue
                }
                delay(sec * 1000L)
                // 不在播时（播放页盖在上面）也刷，但避免和正在播的列表抢 IO：照刷即可
                reload()
            }
        }
    }

    /**
     * 刷新。
     *
     * 以前这里是 `if (loading) return` —— 自动刷新每 20 秒一轮、一轮要 2~6 秒，
     * 用户点刷新时正好撞上进行中的那一轮，点击就被**静默丢掉**了，看起来就是「点了没反应」。
     * 现在：
     *  - 后台轮询（force=false）继续「忙就跳过」，避免请求堆积；
     *  - 用户手动点（force=true）会**等当前这轮结束再跑一轮**，一定拿到最新数据，也一定给反馈。
     */
    suspend fun reload(force: Boolean = false) {
        if (!force && loading) return
        reloadLock.withLock {
            loading = true
            error = null

            // 内置隧道就绪时优先用它给的端口，否则 LiveRepo 还是自己去扫监听端口
            live.preferPort = tunnel.port

            // 首屏（手里一个房间都没有）先只拉前 [QUICK_ROOMS] 个：整池近 2000 个 id、
            // 分 8 片并发也要 2~4 秒，一次性等完再出画面就是"点了没反应"的观感。
            // 先给 500 个把列表铺出来，剩下的在后台再补一趟。
            val quick = liveMode && rooms.isEmpty()
            val result = if (liveMode) {
                live.fetch(limit = if (quick) QUICK_ROOMS else Int.MAX_VALUE)
            } else repo.fetch()

            loading = false
            result.onSuccess {
                applyData(it, fromCache = false)
                tunnelOk = true
                tunnelPort = live.lastPort
                if (quick) {
                    scope.launch {
                        // 补全失败不算错：首屏那批已经在屏幕上了
                        live.fetch().onSuccess { full ->
                            if (full.rooms.size > rooms.size) applyData(full, fromCache = false)
                        }
                    }
                }
            }.onFailure { e ->
                tunnelOk = false
                tunnelPort = 0
                // 实时失败 → 只有手里没数据时才退回远程 JSON，否则别拿旧快照盖掉实时列表
                if (liveMode && rooms.isEmpty()) {
                    val fallback = repo.fetch()
                    fallback.onSuccess {
                        applyData(it, fromCache = false)
                        error = "实时源失败（${e.message}），已降级为 JSON 快照"
                        return@onFailure
                    }
                }
                error = e.message ?: "数据源加载失败"
                if (rooms.isEmpty()) {
                    (live.loadCache() ?: repo.loadCache())?.let { applyData(it, fromCache = true) }
                }
            }
        }
    }

    /** 手动刷新：给 UI 用，结束后把结果写进 [toast] */
    suspend fun refreshManually() {
        reload(force = true)
        toast = when {
            tunnelOk -> "已更新 ${rooms.size} 个直播间"
            error != null -> error
            else -> "已更新 ${rooms.size} 个直播间"
        }
    }

    fun applyPasted(text: String): String? {
        return repo.applyPasted(text)
            .onSuccess { applyData(it, fromCache = false) }
            .exceptionOrNull()?.message
    }

    private fun applyData(data: SourceData, fromCache: Boolean) {
        rooms = data.rooms
        meta = data.meta.copy(fetchedAt = System.currentTimeMillis(), fromCache = fromCache)
        // 服务端关注只有实时源才有；缓存里没有，别拿空集把上次的覆盖掉
        if (!fromCache && liveMode) {
            serverFollowIds = live.followIds
            serverFollowNames = live.followNames
        }
    }

    private companion object {
        /** 首屏只拉这么多房间：2 片 × 250，约 0.6~1 秒出画面 */
        const val QUICK_ROOMS = 500
    }
}
