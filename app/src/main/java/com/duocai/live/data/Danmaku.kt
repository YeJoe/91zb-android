package com.duocai.live.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
 * 弹幕来源。
 *
 * 两条路：
 *   1. **实时**：隧道端口上开 WS 打 `/websock/join`，收 `room_msg` 推送（见 [LiveDanmaku]）
 *   2. **兜底**：隧道还没热起来时，用打包进 APK 的真实样本垫场，避免聊天区空着
 *
 * 实时消息一到，垫场立刻停 —— 不是混着放，是让位。
 */
class DanmakuStore(
    private val context: Context,
    private val prefs: Prefs,
    /** 隧道端口（0 = 还没就绪）；由 AppContainer 接到 LiveRepo/内置隧道上 */
    private val portProvider: () -> Int = { 0 }
) {

    val messages = mutableStateListOf<DmMessage>()

    /** 实时链路的一句话状态，设置页显示用 */
    var status by mutableStateOf("")
        private set

    /** 是否已经收到过实时消息（用来判断垫场该不该让位） */
    var liveCount by mutableStateOf(0)
        private set

    private var job: Job? = null
    private var roomId: Long = 0L
    private var pool: List<DmMessage> = emptyList()

    /** 实时消息计数（跨线程只读，用来判断垫场该不该让位） */
    @Volatile private var seenLive: Int = 0

    private companion object {
        const val MAX_KEEP = 200
        /** 垫场节奏 */
        const val FILL_MS = 1300L
    }

    fun start(scope: CoroutineScope, room: Long) {
        if (job?.isActive == true && roomId == room) return
        job?.cancel()
        job = null
        roomId = room
        messages.clear()
        liveCount = 0
        status = ""

        job = scope.launch {
            if (!prefs.liveMode) {
                status = "离线样本"
                replayDemo()
                return@launch
            }

            seenLive = 0
            // socket 是阻塞 IO，绝不能放在主线程（会直接 ANR）；
            // 用通道把消息送回主线程再上屏，主线程只做状态更新。
            val inbox = Channel<DmMessage>(Channel.UNLIMITED)
            val live = launch(Dispatchers.IO) {
                LiveDanmaku(prefs).run(
                    port = portProvider,
                    roomId = room,
                    onMessage = { m ->
                        seenLive++
                        inbox.trySend(m)
                    },
                    onState = { s -> launch(Dispatchers.Main) { status = s } }
                )
            }
            val consume = launch {
                for (m in inbox) {
                    liveCount++
                    push(m)
                }
            }
            // 垫场：实时一条都没来之前，用样本把聊天区撑住；实时一到立刻让位。
            // 前 8 条走正常节奏，之后放慢，避免隧道一直连不上时刷得太假。
            var i = 0
            while (isActive && consume.isActive && seenLive == 0) {
                if (pool.isEmpty()) {
                    pool = loadDemo().filter { DmFilter.keep(it.kind, prefs.chatOnly) }
                }
                if (pool.isEmpty()) break
                push(pool[i % pool.size])
                i++
                delay(if (i < 8) FILL_MS else 2600L)
            }
            live.join()
        }
    }

    fun restart(scope: CoroutineScope) {
        val r = roomId
        job?.cancel()
        job = null
        messages.clear()
        if (r > 0) {
            roomId = 0L
            start(scope, r)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun push(m: DmMessage) {
        messages.add(m)
        while (messages.size > MAX_KEEP) messages.removeAt(0)
    }

    /** 离线回放：按接近真人的节奏把样本一条条放出去 */
    private suspend fun replayDemo() {
        if (pool.isEmpty()) {
            pool = loadDemo().filter { DmFilter.keep(it.kind, prefs.chatOnly) }
        }
        val list = pool
        if (list.isEmpty()) return
        list.take(6).forEach { push(it) }
        var i = 6
        while (true) {
            push(list[i % list.size])
            i++
            delay(1150L + (i % 4) * 240L)
            if (i % list.size == 0) delay(2600L)
        }
    }

    private fun loadDemo(): List<DmMessage> = runCatching {
        val text = context.assets.open("danmaku_demo.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("msgs") ?: return@runCatching emptyList()
        val out = ArrayList<DmMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t")
            if (t.isBlank()) continue
            val typ = if (o.has("typ") && !o.isNull("typ")) o.optInt("typ") else null
            out.add(
                DmMessage(
                    kind = o.optString("k").ifBlank { DmFilter.classify(typ, t) },
                    typ = typ ?: 0,
                    nick = o.optString("n").ifBlank { "观众" },
                    text = t
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}
