package com.jjlive.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tencent.rtmp.ITXLivePlayListener
import com.tencent.rtmp.TXLiveBase
import com.tencent.rtmp.TXLiveConstants
import com.tencent.rtmp.TXLivePlayConfig
import com.tencent.rtmp.TXLivePlayer
import com.tencent.rtmp.ui.TXCloudVideoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页。
 *
 * 解码链路与原版 App 一致：腾讯云 LiteAV / TXLivePlayer。
 *
 * 为什么不能自己解封装：这个 CDN 下发的是腾讯云私有扩展 FLV —— 序列头是
 * codecId=12(HEVC)，但关键帧 tag 统一标成 0x1d(codecId=13)，载荷不是标准
 * Annex-B NAL，ffmpeg / ExoPlayer 都直接报 "Video codec (d) is not implemented"。
 * 只有 LiteAV 内置的 txffmpeg 认这个封装，所以这里直接复用它的播放器。
 *
 * 流程：/app/live/joinRoom 拿最新地址（pullAddress*，兼容 pullStreamAddr*）
 * → TXLivePlayer.startPlay(url, PLAY_TYPE_LIVE_FLV)。
 */
class PlayerActivity : AppCompatActivity(), ITXLivePlayListener {

    companion object {
        private const val TAG = "PlayerActivity"

        fun start(ctx: Context, room: Room) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra("roomId", room.roomId)
                    .putExtra("name", room.userName)
            )
        }
    }

    private lateinit var store: TokenStore
    private lateinit var api: Api
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var videoBox: FrameLayout
    private lateinit var videoView: TXCloudVideoView
    private lateinit var danmaku: DanmakuView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var overlay: View
    private lateinit var btnRetry: Button

    private var player: TXLivePlayer? = null
    private var im: ImSocket? = null
    private var roomId = ""
    private var roomName = ""
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var videoW = 0
    private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        store = TokenStore(this)
        api = Api(store)
        roomId = intent.getStringExtra("roomId") ?: ""
        roomName = intent.getStringExtra("name") ?: ""

        videoBox = findViewById(R.id.videoBox)
        danmaku = findViewById(R.id.danmaku)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        overlay = findViewById(R.id.overlay)
        btnRetry = findViewById(R.id.btnRetry)

        findViewById<TextView>(R.id.title).text = roomName

        btnRetry.setOnClickListener { fetchAndPlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        // 调试期打开 SDK 控制台日志，便于在 logcat 里看解封装/解码细节
        runCatching { TXLiveBase.setConsoleEnabled(true) }

        setupPlayer()
        fetchAndPlay()
    }

    private fun setupPlayer() {
        try {
            videoView = TXCloudVideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            videoBox.addView(videoView)

            player = TXLivePlayer(this).apply {
                setPlayerView(videoView)
                // 竖屏 1080x1920 的流放到横屏页面上：保持比例、居中留黑边，不拉伸
                setRenderMode(TXLiveConstants.RENDER_MODE_ADJUST_RESOLUTION)
                setRenderRotation(TXLiveConstants.RENDER_ROTATION_0)
                setConfig(
                    TXLivePlayConfig().apply {
                        setAutoAdjustCacheTime(true)
                        setMaxAutoAdjustCacheTime(3f)
                        setMinAutoAdjustCacheTime(1f)
                    }
                )
                setPlayListener(this@PlayerActivity)
                enableHardwareDecode(true)
            }
        } catch (e: Throwable) {
            // 模拟器（x86 转译）或裁剪过的 ROM 上，LiteAV 的 .so 可能加载失败
            Log.e(TAG, "LiteAV init failed", e)
            player = null
            onStatus("播放组件初始化失败：${e.javaClass.simpleName}（该设备可能不支持）")
            btnRetry.visibility = View.VISIBLE
        }
    }

    private fun fetchAndPlay() {
        if (store.token == null) { onStatus("未设置令牌"); return }
        if (player == null) return
        overlay.visibility = View.VISIBLE
        onStatus("正在获取播放地址…")
        urls = emptyList()
        urlIndex = 0

        scope.launch {
            val detail = withContext(Dispatchers.IO) {
                runCatching { api.call("/app/live/joinRoom", mapOf("liveId" to roomId)) }.getOrNull()
            }
            val data = detail?.optJSONObject("data")
            val room = data?.let { Parser.room(it) }

            // 原画 → 720P → 360P
            urls = listOf(room?.url0, room?.url720, room?.url360)
                .filterNotNull().filter { it.isNotBlank() }.distinct()

            if (urls.isEmpty()) {
                onStatus("该房间暂无可用播放地址（可能未开播）")
                btnRetry.visibility = View.VISIBLE
                return@launch
            }

            // 1V1 表演中：服务端不下发媒体，提前提示，不必白等
            val state = data?.optInt("oneToOneState", 1) ?: 1
            if (state == 2) {
                onStatus("主播正在 1V1 表演中，需先购买表演项目才能观看")
                btnRetry.visibility = View.GONE
                return@launch
            }

            onStatus("连接中…")
            playNext()
        }
    }

    /** 依次尝试候选清晰度；播放失败会被切到下一个。 */
    private fun playNext() {
        val p = player ?: return
        if (urlIndex >= urls.size) {
            onStatus("播放失败：所有清晰度都无法播放")
            btnRetry.visibility = View.VISIBLE
            return
        }
        val url = urls[urlIndex++]
        runCatching { p.stopPlay(true) }
        videoW = 0
        videoH = 0
        val ret = p.startPlay(url, TXLivePlayer.PLAY_TYPE_LIVE_FLV)
        Log.i(TAG, "startPlay[$urlIndex/${urls.size}] ret=$ret url=${url.substringBefore('?')}")
        if (ret != 0) {
            // 常见：-5 需要 License、-1 地址非法、-2 无效地址
            onStatus("startPlay 失败 ret=$ret")
            playNext()
            return
        }
        btnRetry.visibility = View.GONE
        if (im == null) startDanmaku()
    }

    private fun startDanmaku() {
        val token = store.token ?: return
        im?.close()
        im = ImSocket(token, store.deviceUid).also { s ->
            s.listener = object : ImSocket.Listener {
                override fun onOpen() {}
                override fun onClosed(reason: String) {}
                override fun onMessage(text: String) {
                    val cn = Regex("\"cn\":\"([^\"]*)\"").find(text)?.groupValues?.getOrNull(1)
                    val body = Regex("\"(?:txt|msg|content)\":\"([^\"]*)\"")
                        .find(text)?.groupValues?.getOrNull(1)
                    val shown = when {
                        !body.isNullOrEmpty() -> body
                        !cn.isNullOrEmpty() -> "$cn 进入直播间"
                        else -> null
                    }
                    shown?.let { danmaku.submit(it) }
                }
            }
            s.connect(roomId)
        }
    }

    // ─────────── ITXLivePlayListener ───────────

    override fun onPlayEvent(event: Int, param: Bundle?) {
        Log.i(TAG, "event ${evName(event)} ($event) ${dumpBundle(param)}")
        when (event) {
            TXLiveConstants.PLAY_EVT_PLAY_BEGIN -> {
                runOnUiThread {
                    overlay.visibility = View.GONE
                    status.text = "播放中"
                }
            }

            TXLiveConstants.PLAY_EVT_CHANGE_RESOLUTION -> {
                val w = param?.getInt(TXLiveConstants.EVT_PARAM1) ?: 0
                val h = param?.getInt(TXLiveConstants.EVT_PARAM2) ?: 0
                if (w > 0 && h > 0) {
                    videoW = w
                    videoH = h
                    runOnUiThread { findViewById<TextView>(R.id.resolution).text = "${w}x$h · LiteAV" }
                }
            }

            TXLiveConstants.PLAY_EVT_PLAY_PROGRESS -> {
                val pos = (param?.getInt(TXLiveConstants.EVT_PLAY_PROGRESS) ?: 0) / 1000
                playSeconds = pos
                runOnUiThread { stats.text = "已播 ${pos}s · ${netKbps} KB/s" }
            }

            TXLiveConstants.PLAY_EVT_PLAY_LOADING -> {
                runOnUiThread { status.text = "缓冲中…" }
            }

            TXLiveConstants.PLAY_EVT_PLAY_END -> {
                runOnUiThread {
                    status.text = "直播已结束"
                    overlay.visibility = View.VISIBLE
                    btnRetry.visibility = View.VISIBLE
                }
            }
        }

        if (event < 0) {
            val desc = param?.getString(TXLiveConstants.EVT_DESCRIPTION).orEmpty()
            Log.w(TAG, "play error event=$event desc=$desc")
            runOnUiThread { status.text = "播放错误($event) $desc" }
            // 拉流失败就换下一档清晰度，全部失败再提示
            if (urlIndex < urls.size) runOnUiThread { playNext() } else runOnUiThread {
                overlay.visibility = View.VISIBLE
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    @Volatile
    private var netKbps = 0
    @Volatile
    private var lastNetLog = 0L

    override fun onNetStatus(bundle: Bundle?) {
        bundle ?: return
        val speed = bundle.getInt(TXLiveConstants.NET_STATUS_NET_SPEED)        // KB/s
        val vBit = bundle.getInt(TXLiveConstants.NET_STATUS_VIDEO_BITRATE)     // kbps
        val aBit = bundle.getInt(TXLiveConstants.NET_STATUS_AUDIO_BITRATE)
        val fps = bundle.getInt(TXLiveConstants.NET_STATUS_VIDEO_FPS)
        val vw = bundle.getInt(TXLiveConstants.NET_STATUS_VIDEO_WIDTH)
        val vh = bundle.getInt(TXLiveConstants.NET_STATUS_VIDEO_HEIGHT)
        netKbps = speed
        if (vw > 0 && vh > 0) { videoW = vw; videoH = vh }

        // 每 2 秒打一次，用来判断「有没有收到码流 / 有没有出帧」
        val now = System.currentTimeMillis()
        if (now - lastNetLog > 2000) {
            lastNetLog = now
            Log.i(
                TAG,
                "net speed=${speed}KB/s vBit=${vBit}kbps aBit=${aBit}kbps fps=$fps " +
                    "size=${vw}x$vh cache=${bundle.getInt(TXLiveConstants.NET_STATUS_VIDEO_CACHE)}"
            )
        }

        runOnUiThread {
            findViewById<TextView>(R.id.resolution).text =
                "${vw}x$vh · vBit=${vBit}k aBit=${aBit} fps=$fps"
            stats.text = "${speed} KB/s · 已播 ${playSeconds}s"
        }
    }

    @Volatile
    private var playSeconds = 0

    private fun evName(e: Int): String = when (e) {
        TXLiveConstants.PLAY_EVT_PLAY_BEGIN -> "PLAY_BEGIN"
        TXLiveConstants.PLAY_EVT_PLAY_PROGRESS -> "PROGRESS"
        TXLiveConstants.PLAY_EVT_PLAY_END -> "PLAY_END"
        TXLiveConstants.PLAY_EVT_PLAY_LOADING -> "LOADING"
        TXLiveConstants.PLAY_EVT_CHANGE_RESOLUTION -> "CHANGE_RESOLUTION"
        TXLiveConstants.PLAY_EVT_RCV_FIRST_I_FRAME -> "RCV_FIRST_I_FRAME"
        TXLiveConstants.PLAY_ERR_NET_DISCONNECT -> "ERR_NET_DISCONNECT"
        else -> "EVT"
    }

    private fun dumpBundle(b: Bundle?): String {
        if (b == null) return "{}"
        val sb = StringBuilder("{")
        for (k in b.keySet()) {
            val v = runCatching { b.get(k) }.getOrNull()
            sb.append(k).append('=').append(v).append(' ')
        }
        return sb.append('}').toString()
    }

    private fun onStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    override fun onDestroy() {
        runCatching { player?.stopPlay(true) }
        runCatching { player?.setPlayerView(null) }
        player = null
        im?.close()
        scope.cancel()
        super.onDestroy()
    }
}
