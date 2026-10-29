package com.jjlive.app

import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 弹幕 / IM 通道。
 *
 * 协议（已用真实连接实测验证）：
 *
 *  连接：
 *    wss://<webSocketDomain>?auth=<authorization>&s=<毫秒时间戳>&aid=<设备ID>&v=<version>
 *
 *  发送（明文 JSON）：
 *    {"type":"1002","toLiveId":"<roomId>"}   进房
 *    {"type":"1003","toLiveId":"<roomId>"}   退房
 *    {"type":"1007"}                         心跳
 *
 *  接收：
 *    Base64 文本 → AES-128-CBC/PKCS7 解密（key = "t4@*77~i&4j#~#c~"，
 *    IV 需从进房帧已知明文推导）→ protobuf，其内嵌 JSON 字段。
 *    实测解出的业务字段：evt / uid / cn / uIc / lv / ses / mid / bet / gn …
 *
 * 注意：只解出第 2 个 16 字节块之后的部分即可读到绝大多数字段
 * （IV 未定只会影响首块），因此这里采用「跳过首块 + 提取 JSON」的兼容策略。
 */
class ImSocket(
    private val token: String,
    private val deviceUid: String,
    private val version: String = Cfg.VERSION,
    private val host: String = Cfg.IM_WS
) {

    interface Listener {
        fun onOpen()
        fun onClosed(reason: String)
        fun onMessage(text: String)
    }

    @Volatile var listener: Listener? = null

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var liveId: String = ""
    @Volatile private var running = false

    private val keyBytes = "t4@*77~i&4j#~#c~".toByteArray(Charsets.UTF_8)

    /** 原版写死的 IV（16 字节），见 _decodeMessage。 */
    private val ivBytes = "16-Bytes--String".toByteArray(Charsets.UTF_8)

    fun connect(roomId: String) {
        liveId = roomId
        running = true
        val url = host + "?auth=" + token +
                "&s=" + System.currentTimeMillis() +
                "&aid=" + deviceUid +
                "&v=" + version
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendJoin()
                listener?.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val plain = decodeFrame(text)
                listener?.onMessage(plain)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener?.onMessage(decodeFrame(bytes.base64()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener?.onClosed(reason.ifEmpty { "code=$code" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener?.onClosed(t.message ?: "连接失败")
            }
        })
    }

    fun sendJoin() {
        if (liveId.isEmpty()) return
        ws?.send("{\"type\":\"1002\",\"toLiveId\":\"$liveId\"}")
    }

    fun sendPing() {
        ws?.send("{\"type\":\"1007\"}")
    }

    fun sendLeave() {
        if (liveId.isEmpty()) return
        ws?.send("{\"type\":\"1003\",\"toLiveId\":\"$liveId\"}")
    }

    fun close() {
        running = false
        try { sendLeave() } catch (_: Exception) {}
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    // ─────────────────────── 解密 ───────────────────────

    /**
     * Base64 → AES 解密。
     *
     * key / iv 都是从原版 Dart 里挖出来的常量（socket_im_manager.dart::_decodeMessage）：
     *   Encrypted.fromUtf8("t4@*77~i&4j#~#c~")  → Key
     *   IV.fromUtf8("16-Bytes--String")        → IV
     * 之前用 key 当 IV，只有首个 16 字节块错，所以只能读到 JSON 的尾巴（"ls":1,"us":1}），
     * 昵称和正文全在前半段，导致聊天区只能显示「xx 进入直播间」。
     *
     * 模式先按 CTR 试（encrypt 包 AES 默认 sic/CTR），再退 CBC/PKCS7。
     */
    private fun decodeFrame(text: String): String {
        val b64 = text.trim().trim('"').replace("\"", "")
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            return text
        }
        if (raw.isEmpty()) return text

        val candidates = listOf("AES/CTR/NoPadding", "AES/CBC/PKCS5Padding")
            .mapNotNull { mode -> runCatching { aes(mode, raw) }.getOrNull() }
        for (c in candidates) if (isJson(c)) return c
        return candidates.firstOrNull() ?: text
    }

    private fun aes(mode: String, raw: ByteArray): String {
        val cipher = Cipher.getInstance(mode)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        val pt = cipher.doFinal(raw)
        return String(pt, Charsets.UTF_8)
    }

    private fun isJson(s: String): Boolean {
        val i = s.indexOf('{')
        if (i < 0) return false
        val tail = s.lastIndexOf('}')
        if (tail <= i) return false
        return runCatching { org.json.JSONObject(s.substring(i, tail + 1)) }.isSuccess
    }
}
