package com.tm.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tm.live.data.DanmakuStore
import com.tm.live.data.FollowStore
import com.tm.live.data.LiveRepo
import com.tm.live.data.Prefs
import com.tm.live.data.Room
import com.tm.live.data.RoomRepo
import com.tm.live.data.SourceData
import com.tm.live.data.SourceMeta
import com.tm.live.player.PlayerHolder
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
    val tunnel = com.tm.live.tunnel.Tunnel(this.context, prefs)

    /**
     * 弹幕端口候选。
     *
     * 关键前提：`/websock/…` 只挂在**业务 nginx** 那个 vhost 上（源 App 的
     * `im_url = http://kiwi_im/tunnel/open?z=1`）。拿列表用的那个端口去连 WS 照样回 101
     * （升级发生在网关层，与 Host 无关），但随后的 `/websock/join` 会被 nginx 404 ——
     * 表现就是「连上了却一条弹幕都收不到」。
     *
     * 端口每次冷启动都会重排（2026-10-30 实测：11689 → 34199 → 37679），
     * 所以候选表必须每轮重连都重新算，不能把端口写死。
     *
     * ## 2026-10-30 修「一直 404」
     *
     * 原候选表有个致命顺序问题：先把 `verifyWsPorts(groupPorts)` 的结果拼进来，
     * 再拼 `probeWsVhosts()`。而这两个方法都依赖**同一时刻**的端口状态 ——
     * 隧道刚 init 完、listener 还没 bind 稳时，两者都为空的概率很高，
     * 于是候选表只剩 `tunnel.port`（`/room` 组），join 必然 404。
     *
     * 现在改成：
     *  1. 两个来源都**不信任缓存**（见 [com.tm.live.data.LiveRepo.verifyWsPorts] 注释），
     *     空结果会立刻重扫；
     *  2. 本机所有 LISTEN 端口也纳入候选 —— 内置隧道自己的 listener 就在里面，
     *     405 判据能把它挑出来（实测一台机器上 24 个监听端口里只有 1 个回 405）；
     *  3. 顺序仍然是「最可能是 IM 的在前」，但不是 IM 的端口也会被**验过**再排除，
     *     而不是凭组名直接丢。
     *
     * ## 分成快慢两档（2026-10-30 第二次改）
     *
     * 上面这套全量候选要扫 24~28 个 LISTEN 端口。但实测 `imPort` 通常就是对的
     * （日志里 `im=38105` 且 join 回 `bad token` —— 路由早就通了，差的是身份）。
     * 所以进房第一轮只打「`imPort` + 上次 405 命中」，这一档**不发任何探测请求**；
     * 全量扫描（下面这个 lambda）只在快路径失败时才跑。
     *
     * 两个 lambda 的分工必须守住：quick 里出现任何网络调用，省下的扫描就白省了。
     */
    val danmaku = DanmakuStore(
        prefs = prefs,
        portsProvider = {
            // 正常情况下候选表至少是「IM 端口 + 列表端口」，实测一台机器 28 个监听端口里
            // 只有 1 个回 405，所以 2 个端口意味着这一轮基本没筛出东西（假阴性）。
            // 假阴性会被 probeWsVhosts 的缓存固化，表现是「一直只试两三个端口全 404」，
            // 因此这里强制重扫一次再交给 WS。
            val first = danmakuCandidates(false)
            if (first.size >= 3) first else danmakuCandidates(true)
        },
        // 快路径：零探测，只读现成的值。顺序 = 「SDK 指的 im」→「上次 405 命中」。
        quickPortsProvider = {
            buildList {
                if (tunnel.imPort > 0) add(tunnel.imPort)
                addAll(live.cachedWsPorts())
            }.filter { it > 0 }.distinct()
        },
        // 发送失败后的重探：**跳过缓存**再扫一遍。隧道端口是每次冷启动重排的，
        // 缓存里的号码可能已经被另一个上游占走（握手 101、请求 500）
        refreshPortsProvider = { danmakuCandidates(true) },
        // 半死状态（列表组在、弹幕组没了）只能重建隧道：换端口/重探都是白试
        rebuildTunnel = {
            val ok = runCatching { tunnel.restart() }.getOrDefault(false)
            if (ok) {
                live.preferPort = tunnel.port
                // 这两个是 Compose 可观察状态，只在主线程改
                scope.launch {
                    tunnelPort = tunnel.port
                    tunnelOk = true
                }
                // 端口整批换了，列表也顺手刷一次
                runCatching { reload(force = true) }
            }
            ok
        }
    )

    /**
     * 弹幕 vhost 的候选端口。
     *
     * 顺序有讲究，别随手调：**本机 listener 实扫排在 SDK 组端口前面**。
     *
     * 2026-10-31 现场：发送一直 500，日志里只试了 `:48325`（旧端口）和 `:43473`，
     * 而此刻真正挂 `/websock` 的是 42561 / 63304。原因就是 `tunnel.groupPorts`
     * 是首次 init 时问 SDK 要的快照 —— 隧道重启后端口整批重排，这份快照全是过期号码，
     * 却排在实扫结果前面，白白占掉前几次尝试。
     *
     * @param force 忽略缓存重扫本机 listener（发送失败后用）
     */
    private fun danmakuCandidates(force: Boolean): List<Int> = buildList {
        // 1. SDK 明确指出的 kiwi_im（Tunnel 重启会更新它，基本可信）
        if (tunnel.imPort > 0) add(tunnel.imPort)

        // 2. 扫本机 listener + 405 判据 —— 唯一反映"此刻"真实端口表的一档
        addAll(live.probeWsVhosts(force))

        // 3. SDK 问到的组端口快照（可能是重启前的，只当补充）
        addAll(live.verifyWsPorts(tunnel.groupPorts.values))

        // 4. 最后兜底：列表用的端口（大概率 404，但聊胜于无）
        if (tunnel.port > 0) add(tunnel.port)
        if (live.lastPort > 0) add(live.lastPort)
    }.filter { it > 0 }.distinct()

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

    /**
     * **手动**刷新（下拉手势 / 右上角刷新按钮）正在进行。
     *
     * 和 [loading] 分开是必须的：[loading] 会被 20 秒一轮的自动轮询点亮，
     * 拿它驱动下拉指示器的话，用户没碰屏幕也会凭空转圈；反过来，界面上的
     * 「刷新中」也该只反映用户自己发起的那次。
     */
    var refreshing by mutableStateOf(false)
        private set

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

    // ---- 关注：服务端 ∪ 本机（并集，不是"服务端唯一真相"） ----
    /**
     * 服务端确认的关注集合（`/user_follow/my_follow` 的 `podcast_id`）。
     *
     * 10-31 一度改成"服务端是唯一真相源"，把本机那份删了。后来实测推翻了：
     * `/relationship/add` 在明文隧道里根本打不到（`docs/关注写接口-实测结论-2026-10-31.md`），
     * 只留服务端那份的话，用户在 App 里点的关注一刷新就全消失。
     *
     * 所以现在是**并集**：`followIdSet()` = 服务端快照 ∪ 本机收藏 ∪ 乐观值。
     * 需要区分来源时看 `FollowScreen` 的「仅本机 · 服务端没有」分组，别在这里做减法。
     */

    /** 源 App 账号在服务端关注的人（`podcast_id` 就是房间号）。实时刷新时更新。 */
    var serverFollowIds by mutableStateOf<Set<Long>>(emptySet())
    var serverFollowNames by mutableStateOf<Map<Long, String>>(emptyMap())

    /**
     * 正在同步中的关注操作（房间号 → 期望状态）。
     *
     * 只影响显示，不落盘：点下去立刻变星标，服务端回执后由
     * [applyFollowSnapshot] 覆盖；失败则回滚。
     */
    private var pendingFollows by mutableStateOf<Map<Long, Boolean>>(emptyMap())

    /** 「关注」页和首页关注 tab 的统一口径：服务端 ∪ 本机 ∪ 正在乐观更新的 */
    fun followIdSet(): Set<Long> {
        val out = serverFollowIds.toMutableSet()
        out.addAll(follows.ids())
        pendingFollows.forEach { (id, want) -> if (want) out.add(id) else out.remove(id) }
        return out
    }

    /** 关注写接口回执（null=空闲）。PlayerScreen 用来弹 toast 告知服务端到底有没有确认。 */
    var followSync by mutableStateOf<String?>(null)
        private set
    /** 自增序号：同一条文案连点两次也能让 LaunchedEffect 重新触发 */
    var followSyncSeq by mutableStateOf(0)
        private set

    /**
     * 消费掉关注回执（弹完 toast 后调用）。
     *
     * 不清空的话，`followSync` 会一直留着上一条文案；播放页重新组合时
     * 就会把「上一次」的关注结果当成新事件再弹一遍 —— 表现就是
     * 「随便点个直播间、没点关注，却弹出服务端未确认」。
     */
    fun consumeFollowSync() {
        followSync = null
    }

    /**
     * 用服务端快照覆盖关注状态，并清掉已完成的乐观更新。
     *
     * @param ids   服务端当前关注集合
     * @param names 房间号 → 昵称（用作「关注」页的副标题）
     */
    fun applyFollowSnapshot(ids: Set<Long>, names: Map<Long, String>) {
        serverFollowIds = ids
        serverFollowNames = names
        // 服务端已经反映的期待状态，就不用再挂着乐观值了
        if (pendingFollows.isNotEmpty()) {
            pendingFollows = pendingFollows.filter { (id, want) -> (id in ids) != want }
        }
    }

    // ---- 本地关注导出 ----
    /** 导出本地关注为 JSON 字符串（剪贴板复制 / SAF 另存文件用，无网络依赖） */
    fun exportFollowsJson(): String = follows.exportJson()

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
                (context.applicationContext as? com.tm.live.App)?.reloadImageLoader()
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

    /**
     * 关注 / 取消关注 —— **先落本机，再试服务端**。
     *
     * ## 为什么又需要本机那份（2026-10-30 线上反馈：点关注只弹「关注失败」）
     *
     * 服务端写接口 `/relationship/add` 并不在房间接口那个 vhost 上：
     *
     * ```
     * :2318（/room/get_list_by_ids、/user_follow/my_follow 都在这）
     *     POST /relationship/add  → nginx 404（路由不在这）
     * :11521 / :44319（唯一认这个路由的两台）
     *     POST /relationship/add  → 403 {"msg":"用户不存在或超时，请重新登录"}
     * ```
     *
     * 也就是说：读接口能用（`my_follow` 200），写接口要的是**另一套登录态**
     * （源 App 走 `/go/` 加密通道 + go 侧 session），手里这枚 JWE 不被它接受。
     * 硬等接口打通 = 用户永远点不动关注，所以改成：
     *
     * ```
     * 点击 → 本机收藏立刻写入（界面一定变）
     *      → 后台仍打一次 /relationship/add
     *         ├─ 服务端确认 → 文案「已关注」，本机那条留着（与服务端一致，无害）
     *         └─ 写不动     → 文案「已关注（仅本机）」，功能照常可用
     * ```
     *
     * 这样既不假装云端成功（文案写清「仅本机」），也不会让按钮变成摆设。
     * 哪天写接口通了，把 [syncFollow] 的返回值当真就行 —— 分支已经在这儿了。
     *
     * ## 2026-10-31 复查（结论更悲观，但上面的设计不用改）
     *
     * 重新扫了当时在听的 **17 个 listener**，`/relationship/add` 全部返回网关兜底的
     * `{"code":0}`（连上面那两台回 403 的都找不到了，重启后端口已换）。也就是说
     * "路由在、只是鉴权不过"的判断不成立，是**当前会话的隧道根本不挂这条路由**。
     * 详见 `docs/关注写接口-实测结论-2026-10-31.md`。
     *
     * 所以"先落本机 + 如实标注"不是临时妥协，而是这个版本下的正确终态；
     * 想真打通得从源 App 的加密通道取会话（Frida / 逆密钥派生），不是改这里。
     */
    fun toggleFollow(room: Room) {
        val willFollow = room.id !in followIdSet()

        // 本机先落：这是"一定生效"的那一份，立刻给反馈（不等服务端，避免 4~5 秒才弹提示）
        follows.setFollowed(room, willFollow)
        pendingFollows = pendingFollows + (room.id to willFollow)
        followSync = if (willFollow) "已关注" else "已取消关注"
        followSyncSeq++

        scope.launch(Dispatchers.IO) {
            // syncFollow 现在立刻返回 false（明文隧道写不动，见 LiveRepo 注释），不再卡住 UI
            val ok = runCatching { live.syncFollow(room.id, willFollow) }.getOrDefault(false)
            pendingFollows = pendingFollows - room.id
            if (ok) {
                // 服务端真通了：用快照补齐（别的设备关注的也能进来）
                runCatching { live.pullFollowSnapshot() }.getOrNull()?.let { (ids, names) ->
                    applyFollowSnapshot(ids, names)
                }
                followSync = if (willFollow) "已关注" else "已取消关注"
            } else {
                // 本机已生效，服务端写不动 → 补「仅本机」后缀（稍后到达即可，不阻塞）
                followSync = if (willFollow) "已关注（仅本机）" else "已取消关注（仅本机）"
            }
            followSyncSeq++
        }
    }

    fun unfollow(id: Long) {
        follows.remove(id)
        pendingFollows = pendingFollows + (id to false)
        // 立刻给反馈
        followSync = "已取消关注"
        followSyncSeq++
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { live.syncFollow(id, false) }.getOrDefault(false)
            if (ok) {
                runCatching { live.pullFollowSnapshot() }.getOrNull()?.let { (ids, names) ->
                    applyFollowSnapshot(ids, names)
                }
            }
            pendingFollows = pendingFollows - id
            followSync = if (ok) "已取消关注" else "已取消关注（仅本机）"
            followSyncSeq++
        }
    }

    /** 「关注」页用：服务端关注里、当前列表能对上的房间 */
    fun followedList(): List<Room> {
        val byId = rooms.associateBy { it.id }
        return followIdSet().mapNotNull { byId[it] }
    }

    /** 冷启动：先出缓存，再拉网络 */
    suspend fun bootstrap() {
        if (loadedOnce) return
        loadedOnce = true
        (live.loadCache() ?: repo.loadCache())?.let { applyData(it, fromCache = true) }

        // 内置隧道单独起一条协程：握手要几秒，不能挡住列表首屏。
        // 它先在后台跑，成功后立刻把手上的端口交给 LiveRepo 并强制刷一轮。
        //
        // 架构门禁放在这里而不是只放 Tunnel 里：x86 设备上一旦进到 dlopen，
        // 加固库的 JNI_OnLoad 会 abort 整个进程，连 catch 的机会都没有。
        if (kiwiAuto && prefs.kiwiAppKey.isNotBlank() &&
            com.tm.live.tunnel.KiwiSdk.abiSupported()
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

            // 手动刷新 = 用户想看「此刻」最新的状态，探活 TTL 得让路。
            // 自动轮询（force=false）不清，否则每 20 秒重探一遍等于回到全量探活。
            if (force) clearProbeState()

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

    /**
     * 手动刷新：给 UI 用，结束后把结果写进 [toast]。
     *
     * 下拉手势和右上角按钮是同一个入口 —— 两边都可能被连点，
     * 用 [refreshing] 挡掉重入：否则连拉两下会排两轮全量抓取，等半天才停。
     */
    suspend fun refreshManually() {
        if (refreshing) return
        refreshing = true
        try {
            reload(force = true)
            toast = when {
                tunnelOk -> "已更新 ${rooms.size} 个直播间"
                error != null -> error
                else -> "已更新 ${rooms.size} 个直播间"
            }
        } finally {
            refreshing = false
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
        // 这里**不再**触发探活：探活改成由列表按「可见窗口」驱动（见 [onFeedVisible]）。
        // 刷了几轮之后探活表里会攒下大量已经不在列表里的房间，顺手清一次，
        // 免得进程长活时这张表越堆越大（真的很大才清，平时不影响 TTL 判定的连续性）。
        if (probeAt.size > rooms.size * 3 + 64) {
            val keepIds = rooms.mapTo(HashSet()) { it.id }
            probeAt.keys.retainAll(keepIds)
            probeResult.keys.retainAll(keepIds)
            aliveIds = probeResult.filterValues { it }.keys.toSet()
            deadIds = probeResult.filterValues { !it }.keys.toSet()
        }
    }

    // ======================= 在播探活（可见窗口 + 20 分钟 TTL） =======================
    //
    // 判据：`http://<域名>/live/<房间号>.flv` 能不能攒出 `FLV` 三个字节（见 LiveRepo 的 flvAlive）。
    // 接口里的 on_air 常年为 1、player_count 下播后不清零，只有这一条靠得住。
    //
    // 为什么不再全量探：id 池 1972 间，24 路并发跑完要发近 2000 条 HTTP、射频连打 40 秒，
    // 每小时最多 12 轮 —— 体感就是「进列表先卡一下」外加掉电快。而卡片是滚着看的，
    // 没滚到的房间探了也没人看。所以：
    //   · 只探「可见区间 + 往后 40 张」（[onFeedVisible] 由列表上报）；
    //   · 同一间房 20 分钟内不重复探（[PROBE_TTL_MS]），滚动来回不重发请求；
    //   · 只在前台、且没进播放页时探（播放页有自己的拉流和弹幕 WS）。
    //
    // 结果**不删房间**，只影响「排序 + 压暗」。删房会让列表在手指底下变短，
    // 也是上一版「刷新后 0 房间」的成因。

    /** 房间 id → 是否可播，只覆盖探过的房间 */
    private val probeResult = HashMap<Long, Boolean>()

    /** 房间 id → 上次探测时刻（毫秒） */
    private val probeAt = HashMap<Long, Long>()

    /** 正在探的房间，防止同一间被并发重复探 */
    private val probeInFlight = HashSet<Long>()

    /** 已确认「此刻拉得到流」的房间 —— 列表置顶用 */
    var aliveIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 已确认「拉不到流」的房间 —— 列表沉底 + 压暗，但**不删除** */
    var deadIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /**
     * 排序版本号。
     *
     * 探活结果是陆续到的，如果每批都立刻重排，用户滚到一半列表会突然换内容。
     * 所以只有「用户停在列表最顶上」时才 +1（见 [markPendingOrder]）：
     * 滚动中只标脏，等滑回顶部再一次性生效。
     */
    var orderEpoch by mutableStateOf(0)
        private set

    private var pendingOrder = false
    private var atTop = true

    private var foregroundState by mutableStateOf(true)

    /** App 是否在前台。MainActivity 的 onResume / onPause 维护 */
    var foreground: Boolean
        get() = foregroundState
        set(v) {
            if (foregroundState == v) return
            foregroundState = v
            // 回到前台要补探一轮：列表布局没变，snapshotFlow 不会自己重发
            if (v) fgEpoch++
        }

    /** 前后台切换次数，列表拿它当「重新上报可见区间」的触发器 */
    var fgEpoch by mutableStateOf(0)
        private set

    /**
     * 最近一次探活的结果，设置页显示用。
     *
     * 这一行是排查「列表全是死房」的第一现场：
     * `本轮 22/60` 说明筛得动；`本轮 0/60` 说明域名或网络有问题，房间本身没问题。
     */
    var probeInfo by mutableStateOf("")
        private set

    /**
     * 列表上报「我现在能看到第 [first] 到第 [last] 张」。
     *
     * 这是唯一的探活入口。顺手把「已经滑回顶部」这件事告诉排序逻辑 ——
     * 攒下的探活结果在这里一次性生效，滚动过程中不动顺序。
     */
    fun onFeedVisible(list: List<Room>, first: Int, last: Int) {
        if (list.isEmpty() || first < 0) return
        atTop = first <= 1
        if (pendingOrder && atTop) {
            pendingOrder = false
            orderEpoch++
        }
        val from = first.coerceIn(0, list.lastIndex)
        val to = (last + PROBE_PREFETCH).coerceIn(from, list.lastIndex)
        ensureProbed(list.subList(from, to + 1))
    }

    /** 探一批。内部再按 TTL 和批量上限裁一次，调用方传整段窗口也不会失控 */
    private fun ensureProbed(window: List<Room>) {
        if (window.isEmpty()) return
        if (!foreground || inPip || openIndex >= 0) return
        val domain = prefs.rtmpDomain
        if (domain.isBlank()) return

        val now = System.currentTimeMillis()
        val todo = ArrayList<Room>(window.size)
        for (r in window) {
            if (r.id in probeInFlight) continue
            val at = probeAt[r.id]
            if (at != null && now - at < PROBE_TTL_MS) continue
            todo.add(r)
            if (todo.size >= PROBE_MAX_BATCH) break
        }
        if (todo.isEmpty()) return

        val ids = todo.map { it.id }
        probeInFlight.addAll(ids)
        scope.launch {
            try {
                val alive = live.probePlayable(todo, domain)
                // 一间都没通 → 多半是网络或流域名的问题。这时候把整屏标成「不在播」
                // 既误导用户、也误导排查，直接丢弃这轮结果。
                if (alive.isEmpty()) {
                    probeInfo = "本轮 0/${todo.size} 可播（网络或流域名异常，结果已丢弃）"
                    return@launch
                }
                val t = System.currentTimeMillis()
                for (r in todo) {
                    probeResult[r.id] = r.id in alive
                    probeAt[r.id] = t
                }
                aliveIds = probeResult.filterValues { it }.keys.toSet()
                deadIds = probeResult.filterValues { !it }.keys.toSet()
                probeInfo = "本轮 ${alive.size}/${todo.size} · 累计 ${aliveIds.size}/${probeResult.size}"
                markPendingOrder()
            } finally {
                probeInFlight.removeAll(ids.toSet())
            }
        }
    }

    /** 探完了：人还在顶部就立刻生效，否则等滑回顶部（见 [orderEpoch]） */
    private fun markPendingOrder() {
        if (atTop) {
            pendingOrder = false
            orderEpoch++
        } else {
            pendingOrder = true
        }
    }

    /** 清空探活缓存。手动刷新时调用 —— 用户点刷新就是想看「此刻」最新的状态 */
    private fun clearProbeState() {
        probeResult.clear()
        probeAt.clear()
        probeInFlight.clear()
        aliveIds = emptySet()
        deadIds = emptySet()
        pendingOrder = false
        probeInfo = ""
        orderEpoch++
    }

    private companion object {
        /** 首屏只拉这么多房间：2 片 × 250，约 0.6~1 秒出画面 */
        const val QUICK_ROOMS = 500

        /** 同一间房的探活结果缓存 20 分钟。滚动来回不会重复发请求 */
        const val PROBE_TTL_MS = 20 * 60 * 1000L

        /** 可见区间之后再多预取多少张，滑一点点不用等下一轮 */
        const val PROBE_PREFETCH = 40

        /** 单批探活上限（可见 + 预取）。防止误传整池导致又变成全量探活 */
        const val PROBE_MAX_BATCH = 160
    }
}
