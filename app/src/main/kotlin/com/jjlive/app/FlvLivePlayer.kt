package com.jjlive.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
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

    /** 实际启用的解码器描述，形如 "OMX.google.hevc.decoder 1080x1920"，用于界面展示。 */
    @Volatile
    var codecLabel: String = ""
        private set

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var nalLenSize = 4
    private var audioConfigured = false
    private var videoConfigured = false
    private var vidCodecId = 12
    private var videoWarned = false
    private var codecFailed = false
    private var audioWarned = false
    private var audioFrames = 0

    // 简单 A/V 同步：以音频时钟为主
    @Volatile private var audioClockUs = 0L
    @Volatile private var audioStartUs = -1L
    @Volatile private var droppedFrames = 0
    private var renderedFrames = 0

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
            Log.i(TAG, "open: $url")
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

    // ─────────────────────── 视频：AVC / HEVC ───────────────────────

    private fun handleVideo(data: ByteArray, tsMs: Long) {
        if (data.size < 5) return
        val b0 = data[0].toInt() and 0xFF
        val frameType = (b0 shr 4) and 0x0F
        val codecId = b0 and 0x0F

        // FLV codecId: 7=AVC(H.264), 12=HEVC(H.265)
        if (codecId != 7 && codecId != 12) {
            if (videoConfigured) return
            post("不支持的视频编码 codecId=$codecId（仅支持 H.264 / H.265）")
            return
        }
        if (codecId != vidCodecId) {
            vidCodecId = codecId
            if (!videoWarned) {
                videoWarned = true
                Log.i(TAG, "video codecId=$codecId (${if (codecId == 7) "H.264" else "H.265"})")
            }
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

    private fun configureVideo(cfg: ByteArray) {
        if (videoConfigured || codecFailed || cfg.size < 7) return
        val isAvc = vidCodecId == 7
        val mime = if (isAvc) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC

        // avcC 第 4 字节 / hvcC 第 21 字节：低 2 位 = lengthSizeMinusOne
        nalLenSize = if (isAvc) (cfg[4].toInt() and 0x03) + 1
        else (if (cfg.size > 21) cfg[21].toInt() and 0x03 else 3) + 1

        // 必须给 MediaFormat 真实宽高。硬件解码器能从 SPS 自己取（传 0×0 也能跑），
        // 但软件解码器（OMX.google.*）会拿 format 宽高去校验裁剪框，0×0 直接
        // "Wrong cropped rect (0,0,0,0) vs. frame (0,0)" → configureCodec 返回 -22。
        val spsSize = runCatching { if (isAvc) parseAvcSize(cfg) else parseHevcSize(cfg) }.getOrNull()
        Log.i(TAG, "sps size: ${spsSize?.first}x${spsSize?.second}")

        val candidates = ArrayList<Pair<Int, Int>>(3)
        spsSize?.let { candidates.add(it) }
        candidates.add(720 to 1280)   // 竖屏直播常见兜底
        candidates.add(1280 to 720)

        var lastError: Exception? = null
        for ((w, h) in candidates) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(mime, w, h)
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg))
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, surface, null, 0)
                codec.start()
                videoCodec = codec
                videoConfigured = true
                codecLabel = "${codec.name} ${w}x$h"
                Log.i(TAG, "video decoder ready: $codecLabel")
                post(if (isAvc) "H.264 解码已启动 ${w}×$h" else "H.265 解码已启动 ${w}×$h")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "configure failed at ${w}x$h: ${e.javaClass.simpleName} ${e.message}")
                try { codec?.release() } catch (_: Exception) {}
            }
        }

        Log.w(TAG, "video configure failed (codecId=$vidCodecId)", lastError)
        post("无法启动解码器：${lastError?.javaClass?.simpleName}（本机可能不支持该编码）")
        // 让页面换下一个清晰度地址再试（只通知一次，避免反复重试）
        if (!codecFailed) {
            codecFailed = true
            listener?.onError("CODEC_FAIL")
        }
    }

    // ─────────── SPS 解析：拿真实分辨率 ───────────

    private fun parseAvcSize(avcC: ByteArray): Pair<Int, Int>? {
        if (avcC.size < 8) return null
        val numSps = avcC[5].toInt() and 0x1F
        if (numSps < 1) return null
        var p = 6
        if (p + 2 > avcC.size) return null
        val spsLen = ((avcC[p].toInt() and 0xFF) shl 8) or (avcC[p + 1].toInt() and 0xFF)
        p += 2
        if (spsLen <= 0 || p + spsLen > avcC.size) return null
        val sps = rbsp(avcC, p, spsLen)
        if (sps.size < 4) return null

        val b = Bits(sps, 1)              // 跳过 1 字节 NAL header
        val profile = b.u(8)
        b.u(8)                            // constraint flags
        b.u(8)                            // level_idc
        b.ue()                            // seq_parameter_set_id

        var chroma = 1
        if (profile in AVC_HIGH_PROFILES) {
            chroma = b.ue()
            if (chroma == 3) b.u(1)
            b.ue()                        // bit_depth_luma_minus8
            b.ue()                        // bit_depth_chroma_minus8
            b.u(1)                        // qpprime_y_zero_transform_bypass
            if (b.u(1) == 1) {            // seq_scaling_matrix_present
                val lists = if (chroma != 3) 8 else 12
                for (i in 0 until lists) {
                    if (b.u(1) == 1) {
                        val count = if (i < 6) 16 else 64
                        var last = 8
                        var next = 8
                        for (j in 0 until count) {
                            if (next != 0) next = (last + b.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        b.ue()                            // log2_max_frame_num_minus4
        when (b.ue()) {                   // pic_order_cnt_type
            0 -> b.ue()
            1 -> {
                b.u(1); b.se(); b.se()
                val n = b.ue()
                for (i in 0 until n) b.se()
            }
        }
        b.ue()                            // max_num_ref_frames
        b.u(1)                            // gaps_in_frame_num_value_allowed
        val wMbs = b.ue() + 1
        val hMap = b.ue() + 1
        val frameOnly = b.u(1)
        if (frameOnly == 0) b.u(1)        // mb_adaptive_frame_field
        b.u(1)                            // direct_8x8_inference

        var w = wMbs * 16
        var h = (2 - frameOnly) * hMap * 16
        if (b.u(1) == 1) {                // frame_cropping_flag
            val l = b.ue(); val r = b.ue(); val t = b.ue(); val bo = b.ue()
            w -= (l + r) * (if (chroma == 1 || chroma == 2) 2 else 1)
            h -= (t + bo) * (if (chroma == 1) 2 else 1)
        }
        return if (w > 0 && h > 0) w to h else null
    }

    private fun parseHevcSize(hvcC: ByteArray): Pair<Int, Int>? {
        if (hvcC.size < 23) return null
        var p = 22
        val numArrays = hvcC[p].toInt() and 0xFF
        p++
        var sps: ByteArray? = null
        for (a in 0 until numArrays) {
            if (p + 3 > hvcC.size) break
            val nalType = hvcC[p].toInt() and 0x3F
            val numNalus = ((hvcC[p + 1].toInt() and 0xFF) shl 8) or (hvcC[p + 2].toInt() and 0xFF)
            p += 3
            for (n in 0 until numNalus) {
                if (p + 2 > hvcC.size) break
                val len = ((hvcC[p].toInt() and 0xFF) shl 8) or (hvcC[p + 1].toInt() and 0xFF)
                p += 2
                if (len <= 0 || p + len > hvcC.size) break
                if (nalType == 33 && sps == null) sps = rbsp(hvcC, p, len)
                p += len
            }
            if (sps != null) break
        }
        val s = sps ?: return null
        if (s.size < 4) return null

        val b = Bits(s, 2)                // 跳过 2 字节 NAL header
        b.u(4)                            // sps_video_parameter_set_id
        val maxSub = b.u(3)               // sps_max_sub_layers_minus1
        b.u(1)                            // sps_temporal_id_nesting_flag

        // profile_tier_level(1, maxSub)
        b.skip(88)                        // general profile/tier/compat/preset(44)
        b.u(8)                            // general_level_idc
        if (maxSub > 0) {
            val prof = BooleanArray(maxSub)
            val lvl = BooleanArray(maxSub)
            for (i in 0 until maxSub) { prof[i] = b.u(1) == 1; lvl[i] = b.u(1) == 1 }
            var i = maxSub
            while (i < 8) { b.u(2); i++ }
            for (j in 0 until maxSub) {
                if (prof[j]) b.skip(88)
                if (lvl[j]) b.u(8)
            }
        }

        b.ue()                            // sps_seq_parameter_set_id
        val chroma = b.ue()
        if (chroma == 3) b.u(1)           // separate_colour_plane_flag
        var w = b.ue()                    // pic_width_in_luma_samples
        var h = b.ue()                    // pic_height_in_luma_samples
        if (b.u(1) == 1) {                // conformance_window_flag
            val l = b.ue(); val r = b.ue(); val t = b.ue(); val bo = b.ue()
            w -= (l + r) * (if (chroma == 1 || chroma == 2) 2 else 1)
            h -= (t + bo) * (if (chroma == 1) 2 else 1)
        }
        return if (w > 0 && h > 0) w to h else null
    }

    /** 去掉防竞争字节 0x03（EBSP → RBSP）。 */
    private fun rbsp(src: ByteArray, from: Int, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(len)
        var zeros = 0
        val end = minOf(src.size, from + len)
        var i = from
        while (i < end) {
            val v = src[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                i++
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    /** 按位读取，MSB 优先。越界抛异常，由外层 runCatching 兜住。 */
    private class Bits(private val d: ByteArray, var bytePos: Int = 0) {
        private var bitPos = 0

        fun u(n: Int): Int {
            var v = 0
            var k = 0
            while (k < n) {
                if (bytePos >= d.size) throw IllegalStateException("bitstream EOF")
                v = (v shl 1) or ((d[bytePos].toInt() shr (7 - bitPos)) and 1)
                if (++bitPos == 8) { bitPos = 0; bytePos++ }
                k++
            }
            return v
        }

        fun skip(n: Int) { var i = 0; while (i < n) { u(1); i++ } }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                if (++zeros > 31) throw IllegalStateException("bad ue(v)")
            }
            return ((1 shl zeros) - 1) + u(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 0) -(k shr 1) else (k + 1) shr 1
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
        // 落后音频 1 秒以上就丢帧，避免延迟累积。
        // 注意方向：视频 pts 小于音频时钟才是"落后"，超前反而要保留。
        // 音频时钟尚未建立时不能判迟到，否则开头会被整段丢掉。
        val synced = audioStartUs >= 0 && audioClockUs > 0
        val late = synced && audioClockUs - ptsUs > 1_000_000
        if (late) { droppedFrames++ ; if (droppedFrames % 60 == 1) post("丢帧追赶中…") }

        while (true) {
            val out = codec.dequeueOutputBuffer(bufInfo, 0)
            when {
                out >= 0 -> {
                    if (!late) {
                        codec.releaseOutputBuffer(out, true)
                        renderedFrames++
                        if (renderedFrames == 1 || renderedFrames % 120 == 0)
                            Log.i(TAG, "rendered frames=$renderedFrames")
                    } else {
                        codec.releaseOutputBuffer(out, false)
                    }
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
        if (soundFormat != 10) {          // 仅 AAC
            if (!audioWarned) {
                audioWarned = true
                Log.w(TAG, "unsupported audio format=$soundFormat")
                post("音频编码不支持（soundFormat=$soundFormat）")
            }
            return
        }
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
            // 先从 ASC 解出采样率与声道数，再建 MediaFormat。
            // 用 0/0 建 format 会让硬解器报 "incorrect numChannels: 0" 后 configure 失败（表现为有画面没声音）。
            val freqIdx = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() shr 7) and 0x01)
            val chanCfg = (asc[1].toInt() shr 3) and 0x0F
            val rate = SAMPLE_RATES.getOrElse(freqIdx) { 44100 }
            val channels = when (chanCfg) {
                7 -> 8
                in 1..6 -> chanCfg
                else -> 2
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(asc))
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            audioCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                // MediaCodec 没有 3 参重载：format, surface, crypto, flags
                configure(format, null, null, 0)
                start()
            }

            val outCh = if (channels == 1) 1 else 2
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                if (outCh == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
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
                            if (outCh == 1) AudioFormat.CHANNEL_OUT_MONO
                            else AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }

            audioConfigured = true
            Log.i(TAG, "audio ready: ${rate}Hz ${channels}ch")
        } catch (e: Exception) {
            Log.w(TAG, "audio init failed", e)
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
                        val size = bufInfo.size
                        if (size > 0) {
                            val chunk = ByteArray(size)
                            ob.get(chunk)
                            ob.clear()
                            try {
                                audioTrack?.write(chunk, 0, chunk.size)
                                audioFrames++
                                if (audioFrames == 1) Log.i(TAG, "first audio pcm: ${size}B")
                                else if (audioFrames % 100 == 0) Log.i(TAG, "audio pcm frames=$audioFrames")
                            } catch (e: Exception) {
                                if (!audioWarned) { audioWarned = true; Log.w(TAG, "audio write failed", e) }
                            }
                        } else ob.clear()
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
        private const val TAG = "FlvLivePlayer"

        /** 这些 profile 的 SPS 里多出 chroma_format_idc 等字段，解析时要跳过。 */
        private val AVC_HIGH_PROFILES =
            intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        /** AAC 采样频率索引表 */
        val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350
        )
    }
}
