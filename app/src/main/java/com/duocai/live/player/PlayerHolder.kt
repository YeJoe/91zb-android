package com.tm.live.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * 全局唯一的播放器。
 *
 * 这是整个 App 最关键的取舍：**全页只有一个 ExoPlayer + 一个 PlayerView**。
 * 抖音式"每页一个播放器"看着好写，实际一次开三个解码器，中低端机直接 OOM。
 * 所以切页时只换 MediaItem，画面靠上层的封面盖住换流的那几百毫秒。
 */
class PlayerHolder(private val context: Context) {

    private var exo: ExoPlayer? = null
    private var currentUrl: String? = null
    private var bufferMs: Int = 1200

    /** 当前房间的候选拉流地址（FLV → RTMP），失败自动换下一个 */
    private var candidates: List<String> = emptyList()
    private var candIndex: Int = 0

    /** 首帧就绪 —— 决定封面要不要淡出 */
    var ready by mutableStateOf(false)

    var playing by mutableStateOf(false)

    var error by mutableStateOf<String?>(null)

    fun player(): ExoPlayer {
        exo?.let { return it }
        val dataSourceFactory = DefaultDataSource.Factory(context, RtmpDataSource.Factory())
        // 兜底：缓冲参数万一下次又被改错，也只退回默认值，不该整个 App 退出
        val loadControl = runCatching { buildLoadControl(bufferMs) }
            .getOrElse { DefaultLoadControl() }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ready = playbackState == Player.STATE_READY
                if (playbackState == Player.STATE_ENDED) {
                    error = "直播已结束"
                    ready = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: PlaybackException) {
                // 还有备选通道就换一个接着试（FLV 不行再试 RTMP）
                if (candIndex + 1 < candidates.size) {
                    candIndex++
                    startUrl(candidates[candIndex], exo?.playWhenReady ?: true)
                    return
                }
                error = describe(e)
                ready = false
            }
        })
        exo = player
        return player
    }

    /**
     * 缓冲档位。ExoPlayer 的 LoadControl 只能在创建时指定（没有 setLoadControl），
     * 所以这里只记下来，下次启动 App 生效 —— 比为了改档位重建解码器划算。
     */
    fun setBuffer(ms: Int) {
        bufferMs = ms
    }

    /**
     * 打开一个房间。传入按优先级排好的候选地址（见 Room.streamUrls）：
     * 第一个失败会自动换下一个，全失败才报错。
     * 同一个房间重复调用不会重新拉流 —— 切回来时靠这个省一次握手。
     */
    fun open(urls: List<String>, autoPlay: Boolean) {
        if (urls.isEmpty()) {
            error = "没填流域名"
            return
        }
        val p = player()
        if (urls == candidates && currentUrl != null) {
            if (autoPlay) p.play()
            return
        }
        candidates = urls
        candIndex = 0
        startUrl(urls[0], autoPlay)
    }

    private fun startUrl(url: String, autoPlay: Boolean) {
        val p = player()
        currentUrl = url
        error = null
        ready = false
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = autoPlay
    }

    /** 手动重试：从第一个候选重新开始 */
    fun retry() {
        if (candidates.isEmpty()) return
        candIndex = 0
        startUrl(candidates[0], player()?.playWhenReady ?: true)
    }

    fun togglePlay() {
        val p = exo ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /** 离开播放页：暂停并丢掉画面，但保留实例，回来时秒开 */
    fun pause() {
        exo?.pause()
    }

    fun release() {
        exo?.release()
        exo = null
        currentUrl = null
        candidates = emptyList()
        candIndex = 0
        ready = false
        playing = false
    }

    /**
     * ExoPlayer 对这四个数有硬约束，违反会直接抛 IllegalArgumentException 闪退：
     * `minBufferMs >= bufferForPlaybackAfterRebufferMs >= bufferForPlaybackMs`，
     * 且 bufferForPlaybackMs > 0。
     *
     * 直播场景的取法：maxBufferMs 放小（不用囤太久），起播门槛压低（进房快），
     * 「卡顿后重新起播」门槛略高于起播门槛（避免网络抖一下就反复起停）。
     */
    private fun buildLoadControl(bufferMs: Int): DefaultLoadControl {
        val target = bufferMs.coerceIn(800, 8_000)                       // 稳态缓冲目标
        val startPlayback = (target / 2).coerceIn(300, 800)              // 首帧起播门槛
        val afterRebuffer = maxOf(startPlayback, minOf(target, 1_500))   // 卡顿后恢复门槛
        val minBuffer = maxOf(target, afterRebuffer)                     // 必须 >= afterRebuffer
        val maxBuffer = maxOf(minBuffer * 3, 12_000)                     // 上限，直播不囤太多
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, startPlayback, afterRebuffer)
            .build()
    }

    private fun describe(e: PlaybackException): String {
        val head = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "连不上服务器"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接超时"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "房间不存在或已下播"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有访问权限"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "流格式不支持"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "流格式不支持"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
            PlaybackException.ERROR_CODE_UNSPECIFIED -> "播放出错"
            else -> "播放出错(${e.errorCode})"
        }
        // 房间快照是静态的，绝大多数"连不上"其实是主播已经下播了 —— 说人话
        val hint = if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ) "（列表是静态快照，这房可能已下播，往下滑换一个）" else ""
        return head + hint
    }
}
