package com.jjlive.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FLV(HEVC) 直播播放器。
 *
 * 为什么不用 Media3：Media3 的 FlvExtractor 写死只支持 H.264
 * （VideoTagPayloadReader 里 videoCodec != 7 直接抛 UnsupportedFormatException），
 * 而源站全部是 HEVC（FLV codecId=12）。这里自己解析 FLV，
 * 直接喂给系统 MediaCodec 硬件解码器 —— 无转码、画质无损、不吃 CPU。
 *
 * 结构：
 *   网络线程：HTTP 拉流 → FLV 解封装 → 分发
 *     - video tag(codecId=12) → 组 Annex-B → HEVC 解码器 → SurfaceView
 *     - audio tag(AAC)        → AAC 解码器 → AudioTrack
 */
class FlvLivePlayer(private val surface: Surface) {

    interface Listener {
        fun onStatus(msg: String)
        fun onVideoSize(w: Int, h: Int)
        fun onStats(bytes: Long, seconds: Int, kbps: Int)
        fun onError(msg: String)
        fun onFinished()
    }

    @Volatile
    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var nalLenSize = 4
    private var audioConfigured = false
    private var videoConfigured = false

    // 简单 A/V 同步：以音频时钟为主
    @Volatile private var audioClockUs = 0L
    @Volatile private var audioStartUs = -1L
    @Volatile private var droppedFrames = 0

    private val bufInfo = MediaCodec.BufferInfo()
    private val scratch = ByteArray(4)

    fun start(url: String) {
        if (running.get()) return
        running.set(true)
        thread = Thread({ playLoop(url) }, "flv-player").also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        release()
    }

    // ─────────────────────── 网络 + 解封装 ───────────────────────

    private fun playLoop(url: String) {
        var conn: HttpURLConnection? = null
        val startMs = System.currentTimeMillis()
        var total = 0L
        var lastReport = startMs
        var lastBytes = 0L

        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "*/*")
            }
            if (conn.responseCode != 200) {
                post("HTTP ${conn.responseCode} ${conn.responseMessage}")
                return
            }

            val input = BufferedInputStream(conn.inputStream, 256 * 1024)
            val head = ByteArray(9)
            if (!readFully(input, head)) { post("流已结束"); return }
            if (String(head, 0, 3) != "FLV") { post("不是 FLV 流"); return }
            val hasAudio = (head[4].toInt() and 0x04) != 0
            val hasVideo = (head[4].toInt() and 0x01) != 0
            if (!readFully(input, scratch)) return   // PreviousTagSize0

            post(if (hasVideo) "已连接，正在解码…" else "流中无视频轨")
            if (!hasVideo) return

            val tagHead = ByteArray(11)
            while (running.get()) {
                if (!readFully(input, tagHead)) break
                val type = tagHead[0].toInt() and 0xFF
                val dataSize = int24(tagHead, 1)
                if (dataSize <= 0 || dataSize > 4 * 1024 * 1024) { post("tag 大小异常: $dataSize"); break }
                // 时间戳是 24 位 + 扩展字节(高 8 位)，整体按 Long 处理
                val ts = int24(tagHead, 4).toLong() or ((tagHead[7].toLong() and 0xFFL) shl 24)
                val data = ByteArray(dataSize)
                if (!readFully(input, data)) break
                if (!readFully(input, scratch)) break       // PreviousTagSize
                total += 11 + dataSize + 4

                when (type) {
                    9 -> handleVideo(data, ts)
                    8 -> handleAudio(data, ts)
                    else -> Unit
                }

                val now = System.currentTimeMillis()
                if (now - lastReport >= 1000) {
                    val secs = ((now - startMs) / 1000).toInt()
                    val kbps = ((total - lastBytes) / 1024).toInt()
                    lastBytes = total
                    lastReport = now
                    postStats(total, secs, kbps)
                }
            }
            post("播放结束")
        } catch (e: Exception) {
            if (running.get()) post("播放中断：${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            release()
            try { conn?.disconnect() } catch (_: Exception) {}
            postFinished()
        }
    }

    // ─────────────────────── 视频：HEVC ───────────────────────

    private fun handleVideo(data: ByteArray, tsMs: Long) {
        if (data.size < 5) return
        val b0 = data[0].toInt() and 0xFF
        val frameType = (b0 shr 4) and 0x0F
        val codecId = b0 and 0x0F

        // FLV codecId: 7=AVC, 12=HEVC
        if (codecId != 12) {
            if (videoConfigured) return
            post("不支持的视频编码 codecId=$codecId（本播放器仅支持 HEVC/H.265）")
            return
        }

        val packetType = data[1].toInt() and 0xFF
        val ctsMs = int24Signed(data, 2).toLong()
        val payload = data.copyOfRange(5, data.size)
        val ptsUs = (tsMs + ctsMs) * 1000

        when (packetType) {
            0 -> configureVideo(payload)
            1 -> decodeVideo(payload, frameType == 1, ptsUs)
            else -> Unit
        }
    }

    private fun configureVideo(hvcC: ByteArray) {
        if (videoConfigured || hvcC.size < 23) return
        try {
            // hvcC 第 21 字节：低 2 位是 lengthSizeMinusOne
            nalLenSize = (hvcC[21].toInt() and 0x03) + 1
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 0, 0)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(hvcC))
            videoCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            videoConfigured = true
            post("HEVC 硬件解码已启动")
        } catch (e: Exception) {
            post("无法启动 HEVC 解码器：${e.javaClass.simpleName}（设备可能不支持 H.265 硬解）")
        }
    }

    private fun decodeVideo(payload: ByteArray, isKey: Boolean, ptsUs: Long) {
        val codec = videoCodec ?: return

        // 长度前缀 NAL → Annex-B 起始码（00 00 00 01），兼容性最好
        val buf = java.io.ByteArrayOutputStream(payload.size + 32)
        var off = 0
        while (off + nalLenSize <= payload.size) {
            var len = 0
            for (i in 0 until nalLenSize) len = (len shl 8) or (payload[off + i].toInt() and 0xFF)
            off += nalLenSize
            if (len <= 0 || off + len > payload.size) break
            buf.write(byteArrayOf(0, 0, 0, 1))
            buf.write(payload, off, len)
            off += len
        }
        val bytes = buf.toByteArray()
        if (bytes.isEmpty()) return

        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: return
                ib.clear()
                ib.put(bytes)
                codec.queueInputBuffer(idx, 0, bytes.size, ptsUs, 0)
            }
            drainVideo(codec, ptsUs)
        } catch (e: Exception) {
            post("解码错误：${e.message}")
        }
    }

    private fun drainVideo(codec: MediaCodec, ptsUs: Long) {
        // 落后音频 1 秒以上就丢帧，避免延迟累积
        val ref = if (audioStartUs >= 0) audioClockUs else ptsUs
        val late = ptsUs - ref > 1_000_000
        if (late) { droppedFrames++ ; if (droppedFrames % 60 == 1) post("丢帧追赶中…") }

        while (true) {
            val out = codec.dequeueOutputBuffer(bufInfo, 0)
            when {
                out >= 0 -> {
                    if (!late) codec.releaseOutputBuffer(out, true)
                    else codec.releaseOutputBuffer(out, false)
                }
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    // getInteger(name, default) 需要 API 29，这里用 containsKey 兼容低版本
                    val w = if (f.containsKey(MediaFormat.KEY_WIDTH))
                        f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (f.containsKey(MediaFormat.KEY_HEIGHT))
                        f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (w > 0 && h > 0) postVideoSize(w, h)
                }
                else -> return
            }
        }
    }

    // ─────────────────────── 音频：AAC ───────────────────────

    private fun handleAudio(data: ByteArray, tsMs: Long) {
        if (data.size < 2) return
        val soundFormat = (data[0].toInt() shr 4) and 0x0F
        if (soundFormat != 10) return      // 仅 AAC
        val packetType = data[1].toInt() and 0xFF
        val payload = data.copyOfRange(2, data.size)
        when (packetType) {
            0 -> configureAudio(payload)
            1 -> decodeAudio(payload, tsMs * 1000)
        }
    }

    private fun configureAudio(asc: ByteArray) {
        if (audioConfigured || asc.size < 2) return
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 0, 0)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(asc))
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            audioCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                // MediaCodec 没有 3 参重载：format, surface, crypto, flags
                configure(format, null, null, 0)
                start()
            }

            // 从 ASC 解出采样率与声道数
            val freqIdx = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() shr 7) and 0x01)
            val chanCfg = (asc[1].toInt() shr 3) and 0x0F
            val rate = SAMPLE_RATES.getOrElse(freqIdx) { 44100 }
            val ch = if (chanCfg >= 2) 2 else 1
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(
                            if (ch == 1) AudioFormat.CHANNEL_OUT_MONO
                            else AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }

            audioConfigured = true
        } catch (e: Exception) {
            post("音频初始化失败：${e.message}（不影响画面）")
        }
    }

    private fun decodeAudio(payload: ByteArray, ptsUs: Long) {
        val codec = audioCodec ?: return
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: return
                ib.clear()
                ib.put(payload)
                codec.queueInputBuffer(idx, 0, payload.size, ptsUs, 0)
            }
            while (true) {
                val out = codec.dequeueOutputBuffer(bufInfo, 0)
                when {
                    out >= 0 -> {
                        val ob = codec.getOutputBuffer(out) ?: continue
                        val chunk = ByteArray(bufInfo.size)
                        ob.get(chunk)
                        ob.clear()
                        try {
                            audioTrack?.write(chunk, 0, chunk.size)
                        } catch (_: Exception) {}
                        if (audioStartUs < 0) audioStartUs = bufInfo.presentationTimeUs
                        audioClockUs = bufInfo.presentationTimeUs
                        codec.releaseOutputBuffer(out, false)
                    }
                    out == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    else -> return
                }
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────── 工具 ───────────────────────

    private fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
        var off = 0
        while (off < len) {
            if (!running.get()) return false
            val n = try { input.read(buf, off, len - off) } catch (_: Exception) { return false }
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun int24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF shl 16) or (b[o + 1].toInt() and 0xFF shl 8) or (b[o + 2].toInt() and 0xFF)

    private fun int24Signed(b: ByteArray, o: Int): Int {
        val v = int24(b, o)
        return if (v and 0x800000 != 0) v - 0x1000000 else v
    }

    private fun release() {
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.flush() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun post(m: String) { listener?.onStatus(m) }
    private fun postVideoSize(w: Int, h: Int) { listener?.onVideoSize(w, h) }
    private fun postStats(b: Long, s: Int, k: Int) { listener?.onStats(b, s, k) }
    private fun postFinished() { listener?.onFinished() }

    companion object {
        /** AAC 采样频率索引表 */
        val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350
        )
    }
}
