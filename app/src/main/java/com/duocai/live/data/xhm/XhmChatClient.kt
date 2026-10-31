package com.tm.live.data.xhm

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tm.live.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 小红帽直播间聊天（弹幕）客户端。
 *
 * 接口文档实测（dex 内常量）：
 *   连接：`wss://api.xt2.club/ws?jwt_token=<会话token>`（HTTP 升级，token 走查询参数）
 *   文本帧是 JSON；收到的消息 kinds 至少有 chat / gift / sys / enter。
 *   由于没抓到线上报文样本，解析是**防御式**的：常见字段名（nick/nickname/userName、
 *   content/msg/text、type/kind/cmd）都认一遍，认不出的帧丢弃（绝不闪退）。
 *
 * demo 模式（无 token）：不连网，用本地定时器模拟进出/聊天消息，
 * 发送时立刻回显一条 —— UI 链路完整可玩，接上真 token 即无缝切真实 WS。
 *
 * 生命周期：一个直播间一个实例（或复用同实例重连）；`close()` 幂等。
 */
class XhmChatClient(private val prefs: XhmPrefs) {

    companion object {
        private const val TAG = "XhmChat"
        /** UI 最多保留多少条历史（老消息丢弃，防长播内存膨胀） */
        private const val MAX_MSGS = 120
    }

    /** 消息列表（倒序 append；UI 直接读它重组） */
    var msgs by mutableStateOf<List<XhmMsg>>(emptyList())
        private set

    /** 连接状态：0 未连 / 1 连接中 / 2 已连 / -1 已断 */
    var state by mutableStateOf(0)
        private set

    private val client = OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val msgSeq = AtomicLong(0)
    private val demoNames = listOf(
        "路过的大猫", "柠檬不酸", "夜航星", "半盏清茶", "风里有诗",
        "软乎乎", "阿洲", "汤汤", "扑克脸", "小满"
    )
    private val demoLines = listOf(
        "主播好漂亮！", "刚来，唱一个？", "666", "灯牌走一波",
        "这歌好听", "来了来了", "关注了", "主播多喝热水"
    )
    private var demoJob: kotlinx.coroutines.Job? = null

    /** 连接指定直播间的聊天通道。重复调用会先断旧连 */
    fun connect(roomId: Long) {
        close()
        if (prefs.demo || prefs.token.isBlank()) {
            startDemo(roomId)
            return
        }
        state = 1
        val base = prefs.baseUrl.trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        // REST 是 https → WS 是 wss；baseUrl 被 http 前缀写过就用 ws
        val scheme = if (prefs.baseUrl.startsWith("http://")) "ws" else "wss"
        val url = "$scheme://$base/ws?jwt_token=${prefs.token}&room_id=$roomId"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state = 2
                sys("已连接直播间聊天")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { append(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                state = -1
                sys("聊天连接断开：${t.message ?: "未知错误"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = -1
            }
        })
    }

    /** 发一条弹幕。demo 模式下直接回显 */
    fun send(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (prefs.demo || prefs.token.isBlank()) {
            append(XhmMsg(msgSeq.incrementAndGet(), "我", t, "chat", now()))
            return true
        }
        val ok = ws?.send(
            JSONObject().apply {
                put("type", "chat")
                put("roomId", currentRoom)
                put("content", t)
            }.toString()
        ) ?: false
        // 乐观回显：服务端确认帧到了会再补一条自己的（按 uid 去重由 UI 层容忍）
        if (ok) append(XhmMsg(msgSeq.incrementAndGet(), "我", t, "chat", now()))
        return ok
    }

    fun close() {
        demoJob?.cancel()
        demoJob = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        state = 0
    }

    fun clear() {
        msgs = emptyList()
    }

    // ------------------------------------------------------------------ 内部

    @Volatile private var currentRoom: Long = 0

    private fun append(m: XhmMsg) {
        val next = ArrayList<XhmMsg>(msgs.size + 1)
        next.addAll(msgs)
        next.add(m)
        msgs = if (next.size > MAX_MSGS) next.subList(next.size - MAX_MSGS, next.size).toList()
        else next
    }

    private fun sys(text: String) {
        append(XhmMsg(msgSeq.incrementAndGet(), "", text, "sys", now()))
    }

    private fun now() = System.currentTimeMillis()

    /** 防御式解析一帧 */
    private fun parse(text: String): XhmMsg? = runCatching {
        val o = JSONObject(text)
        val kind = (o.optString("type").ifBlank { o.optString("kind") })
            .ifBlank { o.optString("cmd") }.ifBlank { "chat" }
        val nick = (o.optString("nick").ifBlank { o.optString("nickname") })
            .ifBlank { o.optString("userName") }.ifBlank { o.optString("uid") }
        val body = (o.optString("content").ifBlank { o.optString("msg") })
            .ifBlank { o.optString("text") }
        if (body.isBlank()) return@runCatching null
        XhmMsg(
            id = o.optLong("id", msgSeq.incrementAndGet()),
            nick = nick,
            text = body,
            kind = kind.lowercase(),
            ts = o.optLong("ts", now())
        )
    }.getOrNull()

    // -------------------------------------------------------------- demo 模拟

    private fun startDemo(roomId: Long) {
        currentRoom = roomId
        state = 2
        msgs = emptyList()
        sys("演示模式：未配置小红帽会话，聊天为本地模拟")
        demoJob = CoroutineScope(Dispatchers.Default).launch {
            var i = 0
            while (true) {
                delay(if (i == 0) 1200L else (2200L + (i * 977L) % 4200L))
                val enter = i % 5 == 3
                append(
                    XhmMsg(
                        id = msgSeq.incrementAndGet(),
                        nick = demoNames[i % demoNames.size],
                        text = if (enter) "进入直播间" else demoLines[i % demoLines.size],
                        kind = if (enter) "enter" else "chat",
                        ts = now()
                    )
                )
                i++
            }
        }
        Log.d(TAG, "demo chat started for room $roomId")
    }
}
