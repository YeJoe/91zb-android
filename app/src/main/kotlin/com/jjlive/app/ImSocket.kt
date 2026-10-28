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
    private val host: String = "wss://yzj.opuxrcm.com/live"
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
     * Base64 → AES-128-CBC 解密。
     *
     * IV 尚未最终确定，而 CBC 模式下 IV 只影响第 1 个 16 字节块。
     * 业务字段（evt/uid/cn/lv/…）都在后续块，因此先用 key 当 IV 解一次，
     * 取第 2 块起的可读部分。
     */
    private fun decodeFrame(text: String): String {
        val raw = try {
            Base64.decode(text.trim().trim('"'), Base64.DEFAULT)
        } catch (_: Exception) {
            return text
        }
        if (raw.isEmpty() || raw.size % 16 != 0) return text

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(keyBytes)
            )
            val pt = cipher.doFinal(raw)
            // IV 未定只影响首个 16 字节块，业务字段都在后续块
            if (pt.size <= 16) return text
            String(pt, 16, pt.size - 16, Charsets.UTF_8)
        } catch (_: Exception) {
            text
        }
    }
}
