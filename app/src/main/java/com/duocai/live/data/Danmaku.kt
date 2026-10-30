package com.tm.live.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DmMessage(val kind: String, val typ: Int, val nick: String, val text: String)

/**
 * 弹幕分类与过滤 —— 只按服务端类型码判断，不看正文。
 *
 * 实测结论（2026-10-30，4 个房间 100 条样本）：
 *   ct=1 typ=32  33 条   普通聊天   ← 留
 *   ct=8 typ=3    1 条   礼物       ← 留（送礼是真人互动）
 *   ct=8 typ=1   42 条   管理操作   ← 丢
 *   ct=8 typ=19   8 条   下注       ← 丢
 *   ct=8 typ=5    6 条   关注       ← 丢
 *   ct=8 typ=20   4 条   结算       ← 丢
 *   ct=8 typ=23   2 条   开奖       ← 丢
 *   ct=8 typ=26   2 条   心愿/引流  ← 丢
 *   ct=8 typ=10   2 条   博彩播报   ← 丢
 *
 * **typ=32 的正文一律原样保留**，不做关键词过滤：真实聊天里也会出现"下注""福利"
 * 这类词，按词杀会把真人也一起杀掉。要干净的只剩 32，就应该只看类型码。
 *
 * **typ=3 的礼物同样保留**：它的 `content` 里没有正文，只有
 * `item_id / count / combos`，正文由 [LiveDanmaku] 拼成「送出礼物 ×N（连击 M）」。
 */
object DmFilter {

    private val TYP_KIND = mapOf(
        1 to "admin", 3 to "gift", 4 to "chat", 5 to "follow", 10 to "ad",
        18 to "sys", 19 to "bet", 20 to "win", 23 to "draw", 26 to "sys",
        32 to "chat", 33 to "order", 34 to "order"
    )

    val KIND_LABEL = mapOf(
        "chat" to "聊天", "gift" to "礼物", "bet" to "下注", "win" to "结算中奖",
        "draw" to "开奖", "follow" to "关注", "admin" to "管理", "ad" to "广告",
        "sys" to "系统", "order" to "下单", "other" to "其它"
    )

    private val BET_WORDS = listOf(
        "下注", "押注", "投注", "结算", "开奖", "派奖", "彩金", "返水", "首充",
        "百家乐", "牛牛", "快三", "时时彩", "六合彩", "棋牌", "电子游艺", "真人视讯",
        "七星", "财神", "提款", "收入囊中", "盘口", "赔率", "跟单", "上分", "下分",
        "东方彩票", "彩票大厅", "赢得", "大廳", "大厅"
    )

    private val AD_WORDS = listOf(
        "速来围观", "已濒临崩溃", "下一秒会怎样", "私聊", "加微", "加V", "加v", "微信",
        "QQ群", "qq群", "电报", "飞机", "邀请码", "注册送", "代理", "包赢", "稳赚",
        "上岸", "计划群", "网址", "点击链接", "复制打开", "下载APP", "下载app"
    )

    private val SYS_WORDS = listOf(
        "完成了主播", "本场心愿", "心愿已达成", "升级为", "开通了", "续费了",
        "欢迎来到", "感谢关注", "排行榜", "活动期间"
    )

    private fun hit(list: List<String>, s: String): String? {
        for (w in list) if (s.contains(w)) return w
        return null
    }

    private fun kindOf(typ: Int?): String = TYP_KIND[typ] ?: "other"

    /** 返回最终类别；"chat" 是唯一可以直接上屏的真人内容 */
    fun classify(typ: Int?, text: String): String {
        val kind = kindOf(typ)
        // 真人聊天：只看类型码，正文原样保留
        if (kind == "chat") return "chat"

        val t = text.trim()
        if (t.isEmpty()) return kind
        // 非聊天类：把伪装成系统消息的博彩/引流挑出来，方便统计
        hit(AD_WORDS, t)?.let { return "ad" }
        hit(BET_WORDS, t)?.let { return "bet" }
        hit(SYS_WORDS, t)?.let { return "sys" }
        return kind
    }

    /**
     * 上屏判定。
     *
     * **礼物始终保留**：送礼是真人花钱产生的互动，不是下注/开奖那类噪声，
     * 所以不管开没开「只显示观众聊天」，`gift` 都要上屏。
     * [chatOnly] 只决定「聊天与礼物以外的杂项」要不要一起显示（默认不要）。
     */
    fun keep(kind: String, chatOnly: Boolean): Boolean = when (kind) {
        "chat", "gift" -> true
        else -> !chatOnly
    }
}

/**
 * 弹幕来源：只有一条路 —— 隧道端口上开 WS 打 `/websock/join`，只收 `room_msg` 实时推送
 * （见 [LiveDanmaku]）。
 *
 * **没有任何垫场/样本/历史回填**：房间里没人说话，聊天区就是空的。
 * 以前这里会在实时消息没到时用固定样本循环顶上，结果每个房间看到的都是同一批假弹幕，
 * 还把"WS 根本没连上"藏得严严实实 —— 这类兜底比没有更糟，所以整个删掉了。
 */
class DanmakuStore(
    private val prefs: Prefs,
    /**
     * 全量隧道端口候选（空 = 还没就绪），越靠前越优先。
     * 这条路径会扫本机全部 LISTEN 端口过 405 判据，**只有快路径失败时才走**。
     */
    private val portsProvider: () -> List<Int> = { emptyList() },
    /**
     * 快路径候选：`Tunnel.imPort` + 上次 405 命中的端口。
     * 要求零网络、零探测 —— 进房第一轮直接打这一两个端口就够了。
     */
    private val quickPortsProvider: () -> List<Int> = { emptyList() },
    /**
     * 强制重探用的候选表：**忽略缓存**重扫一遍（发送失败时才会调）。
     *
     * 与 [portsProvider] 的区别只在缓存策略：那个为了列表速度会复用上一次的探测结果，
     * 而隧道端口在源 App 冷启动后会整体重排 —— 复用等于拿旧号码去撞，全是 500。
     */
    private val refreshPortsProvider: () -> List<Int> = { emptyList() },
    /**
     * 通道整体不可用时重建隧道（`Tunnel.restart`），返回是否成功。
     *
     * ## 为什么必须有这一层（2026-10-31 实测）
     *
     * 隧道端口是分组的，每个组对应一个上游。源 App 退出 / 隧道重连之后，
     * 有可能出现「列表组还在、弹幕组没了」的半死状态：
     *
     * ```
     * 设置页显示「隧道在线 · 515 个直播间 · 端口 56772」 ← 列表正常
     * 但扫完全部 34 个 listener，没有任何一个的 /websock/join 回 200
     * → 怎么发都是 HTTP 500（代理到空上游）
     * ```
     *
     * 这种时候换端口、重探端口全都没用，只有把隧道重启一遍重建分组才恢复。
     * 留着这个回调，用户点发送时就不用自己去设置页点「重启隧道」了。
     */
    private val rebuildTunnel: (suspend () -> Boolean)? = null
) {

    val messages = mutableStateListOf<DmMessage>()

    /** 实时链路的一句话状态，设置页显示用 */
    var status by mutableStateOf("")
        private set

    /** 本次进房已收到的实时消息数 */
    var liveCount by mutableStateOf(0)
        private set

    /**
     * 最近一次连接尝试用的端口候选（设置页诊断用；空 = 还没开始）。
     *
     * 排查「join 404」时最有用：只有 1 个端口说明根本没筛出 `/websock` 的 vhost
     * （内置隧道没问到 kiwi_im、明文 405 探测也没命中），多个端口却全 404 则是另一类问题。
     */
    var ports by mutableStateOf<List<Int>>(emptyList())
        private set

    private var job: Job? = null
    /**
     * 当前这条接收连接的持有者。
     *
     * 发送要复用它拿到的"已进房端口"（[com.tm.live.data.LiveDanmaku.enteredPort]），
     * 所以不能再像以前那样 `LiveDanmaku(prefs)` 建完就丢在 launch 里。
     */
    private var live: LiveDanmaku? = null
    /** 上次重建隧道的时间：失败时别疯狂重启（60 秒一次够了） */
    private var lastRebuildAt: Long = 0L
    private var roomId: Long = 0L
    private var menuId: Int = 1

    private companion object {
        const val MAX_KEEP = 200
    }

    /** 切房入口：房间号 + 分区 id 一起交给 WS，`/websock/join` 两个都要 */
    fun start(scope: CoroutineScope, room: Room) = start(scope, room.id, room.menuId)

    fun start(scope: CoroutineScope, room: Long, menu: Int = 1) {
        if (job?.isActive == true && roomId == room) return
        job?.cancel()
        job = null
        roomId = room
        menuId = if (menu > 0) menu else 1
        messages.clear()
        liveCount = 0
        status = ""
        ports = emptyList()

        job = scope.launch {
            if (!prefs.liveMode) {
                status = "实时弹幕已关闭（设置 → 直播源）"
                return@launch
            }

            // socket 是阻塞 IO，绝不能放在主线程（会直接 ANR）；
            // 用通道把消息送回主线程再上屏，主线程只做状态更新。
            val inbox = Channel<DmMessage>(Channel.UNLIMITED)
            val dm = LiveDanmaku(prefs)
            live = dm
            val link = launch(Dispatchers.IO) {
                dm.run(
                    ports = portsProvider,
                    roomId = room,
                    menuId = menuId,
                    onMessage = { m -> inbox.trySend(m) },
                    onState = { s -> launch(Dispatchers.Main) { status = s } },
                    onPorts = { l -> launch(Dispatchers.Main) { ports = l } },
                    quickPorts = quickPortsProvider
                )
            }
            launch {
                for (m in inbox) {
                    liveCount++
                    push(m)
                }
            }
            link.join()
        }
    }

    fun restart(scope: CoroutineScope) {
        val r = roomId
        val menu = menuId
        job?.cancel()
        job = null
        messages.clear()
        if (r > 0) {
            roomId = 0L
            start(scope, r, menu)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        live = null
    }

    /**
     * 发一条弹幕。
     *
     * 成功后**本地直接上屏**：服务端不会把用户自己发的消息回推给同一条连接
     * （2026-10-30 实测：发完 watch 12 秒收不到，但 history_msg 能拉到），
     * 不回显的话用户会以为没发出去。
     *
     * 昵称写「我」而不是真名：prefs 里只有 token/JWE，没有会话里的房间昵称，
     * 与其显示一串占位 id，不如明确标成自己发的。
     */
    suspend fun send(text: String): Result<Long> {
        val t = text.trim()
        if (t.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        val rid = roomId
        if (rid <= 0L) return Result.failure(IllegalStateException("还没进房"))
        val dm = live ?: return Result.failure(IllegalStateException("弹幕通道还没连上"))

        fun attempt() = runCatching {
            dm.send(
                rid, menuId, t,
                // 第一轮：现成的端口（零探测，最省时间）
                fallbackPorts = { quickPortsProvider() },
                // 第二轮：端口表整张重扫。隧道端口在源 App 冷启动后会重排，
                // 缓存里的号码可能已被别的上游占用（握手照样 101，请求回 500）
                refreshPorts = { refreshPortsProvider().ifEmpty { portsProvider() } }
            )
        }

        val first = attempt()
        if (first.isSuccess) return first.onSuccess { push(DmMessage("chat", 32, "我", t)) }

        // 换端口 + 重探都失败了，说明不是"端口号过期"，而是隧道里**根本没有弹幕上游**
        // （列表组还在、ws 组没了）。实测这种半死状态下无论打哪个端口都是 HTTP 500。
        // 这里重建一次隧道再发 —— 用户不必自己去设置页点「重启隧道」。
        val rebuild = rebuildTunnel
        if (rebuild != null && System.currentTimeMillis() - lastRebuildAt > 60_000L) {
            lastRebuildAt = System.currentTimeMillis()
            status = "弹幕通道断了，正在重建隧道…"
            val ok = runCatching { rebuild() }.getOrDefault(false)
            if (ok) {
                // 新隧道起来后端口会整体换一批，等一下让它把 listener 都 bind 完
                delay(1500)
                val again = attempt()
                if (again.isSuccess) {
                    return again.onSuccess { push(DmMessage("chat", 32, "我", t)) }
                }
                return Result.failure(
                    IllegalStateException(
                        "隧道已重建，但弹幕通道还没就绪，稍等十秒再发（" +
                            (again.exceptionOrNull()?.message ?: "无应答") + "）"
                    )
                )
            }
        }
        first
    }

    private fun push(m: DmMessage) {
        messages.add(m)
        while (messages.size > MAX_KEEP) messages.removeAt(0)
    }
}
