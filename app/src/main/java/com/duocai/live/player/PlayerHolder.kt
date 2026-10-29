package com.duocai.live.player

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

    /** 同一个地址重复调用不会重新拉流 —— 切回来时靠这个省一次握手 */
    fun open(url: String?, autoPlay: Boolean) {
        if (url.isNullOrBlank()) {
            error = "没填 RTMP 域名"
            return
        }
        val p = player()
        if (url == currentUrl) {
            if (autoPlay) p.play()
            return
        }
        currentUrl = url
        error = null
        ready = false
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = autoPlay
    }

    fun togglePlay() {
        val p = exo ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun retry() {
        val p = exo ?: return
        val url = currentUrl ?: return
        error = null
        ready = false
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    /** 离开播放页：暂停并丢掉画面，但保留实例，回来时秒开 */
    fun pause() {
        exo?.pause()
    }

    fun release() {
        exo?.release()
        exo = null
        currentUrl = null
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
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "地址被拒绝"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
            PlaybackException.ERROR_CODE_UNSPECIFIED -> "播放出错"
            else -> "播放出错(${e.errorCode})"
        }
        return head
    }
}
