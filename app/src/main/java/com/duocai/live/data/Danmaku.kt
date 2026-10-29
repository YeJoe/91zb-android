package com.duocai.live.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class DmMessage(val kind: String, val typ: Int, val nick: String, val text: String)

/**
 * 弹幕分类与过滤 —— 规则与 tools/danmaku_filter.py、site/dm_filter.js 保持 1:1。
 *
 * 关键结论（实测 31 个房间 462 条弹幕得出）：
 *   - 真正的类型码在 content.typ，顶层 optcode 在 content_type=8 时是 null
 *   - 32 普通聊天、4 @提及 都要留；19 下注 / 20 结算 / 23 开奖 / 10 博彩播报 / 5 关注 / 1 管理 / 26 心愿 / 33、34 下单 全丢
 *   - 真人聊天只拦链接和联系方式，不做博彩词过滤，否则观众聊"下注"会被误杀
 *   - 引流词只在 >=25 字的句子里判，避免"一对一多久"这种正常提问被干掉
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

    private val SPAM_PATTERNS = listOf("http://", "https://", ".com", ".cn", ".net", "＋v", "+v")

    private val CHAT_AD_WORDS = listOf(
        "加我", "私聊", "加私人", "福利视频", "解锁", "一对一", "包本人",
        "可约", "上门", "秒发", "心愿单下单", "听指挥"
    )

    private val EMOJI_HINT = listOf("💖", "💞", "💦", "🌊", "🛌", "👗", "👍", "🉑", "🌃")

    private const val CHAT_WORD_MIN = 25
    private const val CHAT_LONG = 60
    private const val CHAT_TOO_LONG = 110

    private fun hit(list: List<String>, s: String): String? {
        for (w in list) if (s.contains(w)) return w
        return null
    }

    private fun kindOf(typ: Int?): String = TYP_KIND[typ] ?: "other"

    /** 返回最终类别；返回 "ad" 表示这条不能显示 */
    fun classify(typ: Int?, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return kindOf(typ)
        var kind = kindOf(typ)

        if (kind == "chat") {
            if (hit(SPAM_PATTERNS, t.lowercase()) != null) return "ad"
            if (t.length >= CHAT_WORD_MIN && hit(CHAT_AD_WORDS, t) != null) return "ad"
            if (t.length >= CHAT_TOO_LONG) return "ad"
            if (t.length >= CHAT_LONG) {
                var emo = 0
                for (e in EMOJI_HINT) {
                    var p = t.indexOf(e)
                    while (p >= 0) {
                        emo++
                        p = t.indexOf(e, p + e.length)
                    }
                }
                if (emo >= 2) return "ad"
            }
            return "chat"
        }

        // 非聊天类：先用关键词把伪装成系统消息的广告/博彩挑出来
        hit(AD_WORDS, t)?.let { return "ad" }
        hit(BET_WORDS, t)?.let { return "bet" }
        hit(SYS_WORDS, t)?.let { return "sys" }
        return kind
    }

    fun keep(kind: String, chatOnly: Boolean): Boolean =
        if (chatOnly) kind == "chat" else (kind == "chat" || kind == "gift")
}

/**
 * 弹幕来源。
 *
 * 现在没有可用的 WS 端点（公网反代要用户自备），所以这里做两件事：
 *   1. 有 ws 地址就（未来版本）订阅，目前留占位
 *   2. 没有就用打包进 APK 的真实样本按接近实时的节奏回放
 * 这样聊天区不会空着，过滤规则也能被用户直接看见。
 */
class DanmakuStore(private val context: Context, private val prefs: Prefs) {

    val messages = mutableStateListOf<DmMessage>()

    private var job: Job? = null
    private var pool: List<DmMessage> = emptyList()
    private var loaded = false

    private companion object {
        const val MAX_KEEP = 200
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!loaded) {
                pool = loadDemo()
                loaded = true
            }
            val list = pool.filter { DmFilter.keep(it.kind, prefs.chatOnly) }
            if (list.isEmpty()) return@launch
            if (messages.isEmpty()) {
                // 先垫几条，进房就有内容
                list.take(6).forEach { messages.add(it) }
            }
            var i = 6
            while (isActive) {
                messages.add(list[i % list.size])
                while (messages.size > MAX_KEEP) messages.removeAt(0)
                i++
                delay(1150L + (i % 4) * 240L)
                if (i % list.size == 0) delay(2600L)
            }
        }
    }

    fun restart(scope: CoroutineScope) {
        job?.cancel()
        job = null
        messages.clear()
        start(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
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
            out.add(DmMessage(
                kind = o.optString("k").ifBlank { DmFilter.classify(typ, t) },
                typ = typ ?: 0,
                nick = o.optString("n").ifBlank { "观众" },
                text = t
            ))
        }
        out
    }.getOrDefault(emptyList())
}
