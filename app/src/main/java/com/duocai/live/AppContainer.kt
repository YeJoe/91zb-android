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
    val danmaku = DanmakuStore(this.context, prefs)
    val player = PlayerHolder(this.context)

    /** 进程级作用域，承载自动刷新循环（App 活着就一直在） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var refreshStarted = false

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

    // ---- 导航 ----
    var tab by mutableStateOf(Tab.HOME)
    /** 首页一级 tab。放在容器里而不是 HomeScreen 局部，切到别的页再回来才不会重置 */
    var feed by mutableStateOf(Feed.RECOMMEND)
    /** 当前播放页的房间序列（列表可能被分类/搜索筛过，翻页要跟着筛过的走） */
    var playlist by mutableStateOf<List<Room>>(emptyList())
    var openIndex by mutableStateOf(-1)

    // ---- 关注（ids 是快照，供 Compose 观察）----
    var followedIds by mutableStateOf<Set<Long>>(emptySet())
    /** 源 App 账号在服务端关注的人（`podcast_id` 就是房间号）。实时刷新时更新。 */
    var serverFollowIds by mutableStateOf<Set<Long>>(emptySet())
    var serverFollowNames by mutableStateOf<Map<Long, String>>(emptyMap())

    /** 本地收藏 ∪ 服务端关注 —— 「关注」页和首页关注 tab 都用这一个口径 */
    fun followIdSet(): Set<Long> = followedIds + serverFollowIds

    // ---- 设置里会被界面直接读的几项，做成可观察 ----
    private var showAdState by mutableStateOf(prefs.showAd)
    var showAd: Boolean
        get() = showAdState
        set(v) {
            showAdState = v
            prefs.showAd = v
        }

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

    /** 实时模式开关也放到容器里，避免和设置页的局部 state 打架 */
    private var liveModeState by mutableStateOf(prefs.liveMode)
    var liveMode: Boolean
        get() = liveModeState
        set(v) {
            liveModeState = v
            prefs.liveMode = v
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

    suspend fun reload() {
        if (loading) return
        loading = true
        error = null

        // 实时模式：直连隧道端口（拿得到就是最新，拿不到再降级到 JSON）
        val result = if (liveMode) live.fetch() else repo.fetch()

        loading = false
        result.onSuccess { applyData(it, fromCache = false) }
            .onFailure { e ->
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
}
