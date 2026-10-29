package com.duocai.live.data

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.zip.Inflater

/**
 * 直播间实时弹幕。
 *
 * 走的是隧道端口上的那条明文长连接（`/tunnel/open` → WebSocket → `/websock/join`），
 * 也就是源 App 自己用的同一条通道。协议是 2026-10-30 用 tools/live_ws.py 抓通的，
 * 这里 1:1 复刻，不引第三方 WS 库（项目里只有 Coil/Media3，没有 OkHttp）。
 *
 * ## 帧流程
 *
 * ```
 * GET /tunnel/open?z=1&appversion=…&token=<32hex>      101 Switching Protocols
 *   authorization: Bearer <JWE>
 *   cipher: <b64 8B>            ← 隧道侧要的，随机即可
 *
 * → {"uri":"/websock/join","method":"POST","random":7001,"body":{…}}
 * ← {"opt":"r","ctl":"room_msg","data":"[{…}]"}            ← 实时推送，可能整帧 zlib
 * ← {"opt":"http","data":{"random":7001,"status":200,…}}   ← 请求应答
 * ```
 *
 * ## 为什么要自己解 zlib
 *
 * 握手时带了 `permessage-deflate`，服务端会把整帧压成 deflate；但有些帧又是裸 JSON。
 * 所以解码顺序是「先当 JSON 试 → 再按带 zlib 头解 → 最后按裸 deflate 解」，
 * 和 tools/live_ws.py 的 `decode()` 完全一致。
 */
class LiveDanmaku(private val prefs: Prefs) {

    /** 最近一次失败原因，设置页直接显示 */
    @Volatile var lastError: String = ""
        private set

    private val rnd = SecureRandom()

    /** (nick|text) -> 上次出现的时间戳，用来压掉 history/push 重叠带来的重复 */
    private val seen = HashMap<String, Long>()

    suspend fun run(
        port: () -> Int,
        roomId: Long,
        onMessage: (DmMessage) -> Unit,
        onState: (String) -> Unit
    ) {
        var backoff = 1500L
        while (currentCoroutineContext().isActive) {
            val p = port()
            if (p <= 0) {
                onState("等待隧道")
                delay(3000)
                continue
            }
            try {
                onState("连接中")
                pumpOnce(p, roomId, onMessage, onState)
                backoff = 1500L          // 正常断开（服务端关连接）就快速重连
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                onState("断开重连：" + lastError)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15000L)
        }
    }

    // ---------------------------------------------------------------- 一次连接
    private suspend fun pumpOnce(
        port: Int,
        roomId: Long,
        onMessage: (DmMessage) -> Unit,
        onState: (String) -> Unit
    ) {
        val socket = Socket()
        val jobs: Job? = currentCoroutineContext()[Job]
        // 协程被取消时阻塞读不会自己醒，靠这里把 socket 关掉打断它
        val hook = jobs?.invokeOnCompletion { runCatching { socket.close() } }
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 4000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000

            if (!handshake(socket, port)) {
                throw IllegalStateException("WebSocket 握手被拒（端口 $port 不挂业务 vhost，或 token 失效）")
            }
            onState("已连接")

            val out = socket.getOutputStream()
            // 先拉一屏历史，进房立刻有内容；失败不影响后续推送
            sendCall(out, "/websock/join", JSONObject()
                .put("room_id", roomId)
                .put("menu_id", JOIN_MENU)
                .put("cp_type", 3), 7001)

            val inp = socket.getInputStream()
            while (currentCoroutineContext().isActive) {
                val frame = try {
                    readFrame(inp)
                } catch (e: SocketTimeoutException) {
                    continue                    // 房间安静，继续等
                } ?: break

                when (frame.opcode) {
                    0x1, 0x2 -> {
                        val obj = decode(frame.payload) ?: continue
                        when (obj.optString("opt")) {
                            "r", "u" -> handlePush(obj, onMessage)
                            "http" -> {
                                val st = obj.optJSONObject("data")?.optInt("status", 0) ?: 0
                                if (st == 200) onState("已进房")
                            }
                        }
                    }
                    0x8 -> break                 // close
                    0x9 -> writeFrame(out, 0xA, frame.payload)   // ping → pong
                    else -> Unit
                }
            }
        } finally {
            hook?.dispose()
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 握手
    private fun handshake(socket: Socket, port: Int): Boolean {
        val key = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(8).also { rnd.nextBytes(it) }
        val token = prefs.appToken.trim()
        val jwe = prefs.jwe.trim()

        val sb = StringBuilder()
        sb.append("GET /tunnel/open?z=1&appversion=").append(APP_VERSION)
        if (token.isNotEmpty()) sb.append("&token=").append(token)
        sb.append(" HTTP/1.1\r\n")
        sb.append("user-agent: Dart/3.10 (dart:io)\r\n")
        sb.append("connection: Upgrade\r\n")
        sb.append("cache-control: no-cache\r\n")
        sb.append("accept-encoding: gzip\r\n")
        sb.append("sec-websocket-version: 13\r\n")
        sb.append("host: 127.0.0.1:").append(port).append("\r\n")
        if (jwe.isNotEmpty()) sb.append("authorization: Bearer ").append(jwe).append("\r\n")
        sb.append("sec-websocket-extensions: permessage-deflate; client_max_window_bits\r\n")
        sb.append("sec-websocket-key: ").append(b64(key)).append("\r\n")
        sb.append("cipher: ").append(b64(iv)).append("\r\n")
        sb.append("upgrade: websocket\r\n\r\n")

        socket.getOutputStream().apply {
            write(sb.toString().toByteArray(Charsets.UTF_8))
            flush()
        }

        val inp = socket.getInputStream()
        val head = StringBuilder()
        val one = ByteArray(1)
        while (!head.endsWith("\r\n\r\n")) {
            val n = try {
                inp.read(one)
            } catch (e: SocketTimeoutException) {
                return false
            }
            if (n <= 0) return false
            head.append((one[0].toInt() and 0xFF).toChar())
            if (head.length > 8192) return false
        }
        return head.contains(" 101 ")
    }

    // ---------------------------------------------------------------- 收 / 解析
    private fun handlePush(o: JSONObject, onMessage: (DmMessage) -> Unit) {
        if (o.optString("ctl") != "room_msg") return
        val arr = when (val raw = o.opt("data")) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull() ?: return
            else -> return
        }
        val now = System.currentTimeMillis()

        /**
         * 去重 + 上抛。
         *
         * 同一条消息会同时从 `history`（进房补的历史）和 `push`（实时推送）来两遍，
         * 服务端又给了一份自增 [JSONObject] `id`，所以优先按 id 去重（窗口 2 分钟），
         * 同时保留 `昵称|正文` 作为兜底 —— 实测 history 与 push 的 id 偶尔对不上。
         *
         * [pairTtl] 给礼物留了短窗口：连击时同一人连送十次、正文完全一样，
         * 用聊天的 15 秒窗口会把后面九次全吃掉。
         */
        fun emit(item: JSONObject, nick: String, kind: String, typ: Int, text: String, pairTtl: Long) {
            if (text.isBlank()) return
            val mid = item.optLong("id", 0L)
            val pairKey = "$nick|$text"
            val idKey = if (mid > 0L) "id:$mid" else null
            val dupPair = seen[pairKey]?.let { now - it < pairTtl } == true
            val dupId = idKey != null && seen[idKey]?.let { now - it < 120_000L } == true
            if (dupPair || dupId) return
            seen[pairKey] = now
            if (idKey != null) seen[idKey] = now
            onMessage(DmMessage(kind, typ, nick, text))
        }

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val content = asObject(item.opt("content")) ?: continue
            val typ = if (content.has("typ")) content.optInt("typ", -1)
            else content.optInt("optcode", -1)
            val nick = item.optString("nickname").ifBlank { "观众" }

            when (typ) {
                // 真人聊天：32 普通聊天 / 4 @提及。正文原样保留 —— 不做关键词过滤
                //（真实聊天里也会出现"下注""福利"，按词杀会把真人也杀掉）。
                32, 4 -> emit(item, nick, "chat", typ, content.optString("text").trim(), 15_000L)

                // 礼物：content 里没有 text，只有 item_id / count / combos，
                // 得自己拼一句。样本实测 typ=3 形如
                //   {"typ":3,"optcode":3,"count":1,"item_id":5040,"combos":3,"vip_level":0}
                // combos 只有 >1 才写出来（"连击 N"），它的确切语义还没验完 ——
                // 但服务端在连击时确实会连着推多条同样的 count=1，靠这个字段能区分。
                3 -> {
                    val count = content.optInt("count", 1).coerceAtLeast(1)
                    val combos = content.optInt("combos", 0)
                    val text = buildString {
                        append("送出礼物 ×").append(count)
                        if (combos > 1) append("（连击 ").append(combos).append("）")
                    }
                    emit(item, nick, "gift", 3, text, 1_200L)
                }

                // 其余（下注 19 / 结算 20 / 开奖 23 / 关注 5 / 管理 1 / 下单 33·34 …）一律丢
                else -> Unit
            }
        }

        // 清掉过期键，别让 seen 无限长
        if (seen.size > 800) {
            val cut = now - 180_000L
            seen.entries.removeAll { it.value < cut }
        }
    }

    private fun asObject(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null
    }

    private fun decode(data: ByteArray): JSONObject? {
        runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()?.let { return it }
        for (nowrap in booleanArrayOf(true, false)) {
            val s = inflate(data, nowrap) ?: continue
            runCatching { JSONObject(s) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): String? = runCatching {
        val inf = Inflater(nowrap)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4 + 64)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inf.end()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 帧
    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun sendCall(out: OutputStream, uri: String, body: JSONObject, random: Int) {
        val o = JSONObject()
            .put("body", body)
            .put("random", random)
            .put("method", "POST")
            .put("uri", uri)
        writeFrame(out, 0x1, o.toString().toByteArray(Charsets.UTF_8))
    }

    /** 客户端帧必须加掩码（RFC 6455 5.3），服务端会直接断没有掩码的连接 */
    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> head.write(0x80 or n)
            n < 65536 -> {
                head.write(0x80 or 126)
                head.write((n ushr 8) and 0xFF)
                head.write(n and 0xFF)
            }
            else -> {
                head.write(0x80 or 127)
                var v = n.toLong()
                val tmp = ByteArray(8)
                for (i in 7 downTo 0) {
                    tmp[i] = (v and 0xFF).toByte()
                    v = v ushr 8
                }
                head.write(tmp)
            }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        head.write(mask)
        val body = ByteArray(n)
        for (i in 0 until n) body[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readFrame(inp: InputStream): Frame? {
        val h = ByteArray(2)
        if (!readFully(inp, h)) return null
        val opcode = h[0].toInt() and 0x0F
        val masked = (h[1].toInt() and 0x80) != 0
        var len = (h[1].toInt() and 0x7F).toLong()
        if (len == 126L) {
            val b = ByteArray(2)
            if (!readFully(inp, b)) return null
            len = ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toLong()
        } else if (len == 127L) {
            val b = ByteArray(8)
            if (!readFully(inp, b)) return null
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
            len = v
        }
        if (len < 0 || len > MAX_FRAME) return null
        val mask = if (masked) ByteArray(4).also { if (!readFully(inp, it)) return null } else null
        val data = ByteArray(len.toInt())
        if (len > 0 && !readFully(inp, data)) return null
        if (mask != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return Frame(opcode, data)
    }

    private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object {
        /** 源 App 的版本号，服务端会校验 */
        const val APP_VERSION = "20260226001"
        const val JOIN_MENU = 1
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
