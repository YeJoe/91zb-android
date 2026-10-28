package com.jjlive.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页。
 *
 * 流程：调 /app/live/joinRoom 拿最新播放地址（字段 pullAddress*，
 * 同时兼容列表接口的 pullStreamAddr*）→ FlvLivePlayer 硬解 HEVC。
 */
class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback, FlvLivePlayer.Listener {

    companion object {
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

    private lateinit var surface: SurfaceView
    private lateinit var danmaku: DanmakuView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var overlay: View
    private lateinit var btnRetry: Button

    private var player: FlvLivePlayer? = null
    private var im: ImSocket? = null
    private var roomId = ""
    private var roomName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        store = TokenStore(this)
        api = Api(store)
        roomId = intent.getStringExtra("roomId") ?: ""
        roomName = intent.getStringExtra("name") ?: ""

        surface = findViewById(R.id.surface)
        danmaku = findViewById(R.id.danmaku)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        overlay = findViewById(R.id.overlay)
        btnRetry = findViewById(R.id.btnRetry)

        findViewById<TextView>(R.id.title).text = roomName
        surface.holder.addCallback(this)

        btnRetry.setOnClickListener { fetchAndPlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        fetchAndPlay()
    }

    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.stop(); player = null
    }

    private fun fetchAndPlay() {
        if (store.token == null) { onStatus("未设置令牌"); return }
        overlay.visibility = View.VISIBLE
        onStatus("正在获取播放地址…")

        scope.launch {
            // 进房接口拿最新地址；失败则退回列表里带来的（此处重新请求列表）
            val detail = withContext(Dispatchers.IO) {
                runCatching { api.call("/app/live/joinRoom", mapOf("liveId" to roomId)) }.getOrNull()
            }
            val data = detail?.optJSONObject("data")
            val url = data?.let { Parser.room(it).bestUrl() } ?: ""

            if (url.isEmpty()) {
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
            startPlayer(url)
        }
    }

    private fun startPlayer(url: String) {
        player?.stop()
        player = FlvLivePlayer(surface.holder.surface).also {
            it.listener = this
            it.start(url)
        }
        btnRetry.visibility = View.GONE
        startDanmaku()
    }

    private fun startDanmaku() {
        val token = store.token ?: return
        im?.close()
        im = ImSocket(token, store.deviceUid).also { s ->
            s.listener = object : ImSocket.Listener {
                override fun onOpen() { runOnUiThread { onStatus("已连接弹幕") } }
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

    // ─────────── FlvLivePlayer.Listener ───────────

    override fun onStatus(msg: String) {
        runOnUiThread {
            status.text = msg
            if (msg.contains("解码") || msg.contains("已连接")) overlay.visibility = View.GONE
        }
    }

    override fun onVideoSize(w: Int, h: Int) {
        runOnUiThread { findViewById<TextView>(R.id.resolution).text = "${w}x${h} · HEVC 硬解" }
    }

    override fun onStats(bytes: Long, seconds: Int, kbps: Int) {
        runOnUiThread {
            stats.text = "已播 ${seconds}s · ${kbps} KB/s · ${bytes / 1024} KB"
            if (seconds >= 30) stats.append(" · 已越过 30s 限制")
        }
    }

    override fun onError(msg: String) { onStatus(msg) }

    override fun onFinished() {
        runOnUiThread {
            overlay.visibility = View.VISIBLE
            btnRetry.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        player?.stop()
        im?.close()
        scope.cancel()
        super.onDestroy()
    }
}
