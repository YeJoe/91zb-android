package com.jjlive.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 播放页。
 *
 * 解码链路与原版网页端一致：jessibuca(WebAssembly) 软解 + canvas 渲染，跑在 WebView 里。
 *
 * 为什么不用原生解码：这个 CDN 下发的是腾讯云私有扩展 FLV —— 序列头标 codecId=12(HEVC)，
 * 但 I 帧 tag 标成 0x1d(frametype=1, codecId=13)，载荷既不是 4 字节长度前缀的 NAL 链，
 * 也不是 Annex-B（整段找不到 00 00 01 起始码）。ffmpeg/ExoPlayer/手写解封装全都解不出来，
 * 只有 jessibuca/txffmpeg 这类认私有封装的解封装器能吃。网页版已经用 jessibuca 验证过
 * 能正常出画面，所以这里直接把同一套 wasm 播放器嵌进 WebView，链路完全一致。
 *
 * 流程：/app/live/joinRoom 拿最新地址（pullAddress*，兼容 pullStreamAddr*）
 * → WebView 里 startStream(url) → 失败自动切下一档清晰度。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/web/player.html"

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
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var videoBox: FrameLayout
    private lateinit var danmaku: DanmakuView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var resolution: TextView
    private lateinit var overlay: View
    private lateinit var btnRetry: Button

    private var web: WebView? = null
    private var im: ImSocket? = null
    private var roomId = ""
    private var roomName = ""

    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var pageReady = false
    private var playing = false
    private var playedSeconds = 0
    private var playStartAt = 0L

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
        resolution = findViewById(R.id.resolution)
        overlay = findViewById(R.id.overlay)
        btnRetry = findViewById(R.id.btnRetry)

        findViewById<TextView>(R.id.title).text = roomName

        btnRetry.setOnClickListener { fetchAndPlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        setupWebView()
        fetchAndPlay()
    }

    // ─────────── WebView + jessibuca ───────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            // 自己指定 MIME：decoder.wasm 必须是 application/wasm，
            // 否则 instantiateStreaming 失败（虽然 emscripten 有兜底，但能省一次往返）
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", object : WebViewAssetLoader.PathHandler {
                    override fun handle(path: String): WebResourceResponse? {
                        val mime = when {
                            path.endsWith(".wasm") -> "application/wasm"
                            path.endsWith(".js") -> "application/javascript"
                            path.endsWith(".html") -> "text/html"
                            path.endsWith(".json") -> "application/json"
                            else -> "application/octet-stream"
                        }
                        val text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
                        return try {
                            WebResourceResponse(mime, if (text) "utf-8" else null, assets.open(path)).apply {
                                setResponseHeaders(
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Cache-Control" to "no-cache"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "asset miss: $path", e)
                            null
                        }
                    }
                })
                .build()

            val w = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // wasm 软解要内存，也给解码线程留出空间
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageReady = true
                        Log.i(TAG, "page finished: $url")
                        pendingUrl?.let { u ->
                            pendingUrl = null
                            eval("startStream(${JSONObject.quote(u)})")
                        }
                    }
                }
                addJavascriptInterface(Bridge(), "Native")
            }
            web = w
            videoBox.addView(w)
            w.loadUrl(PAGE_URL)
        } catch (e: Throwable) {
            Log.e(TAG, "WebView init failed", e)
            web = null
            onStatus("播放组件初始化失败：${e.javaClass.simpleName}")
            btnRetry.visibility = View.VISIBLE
        }
    }

    private var pendingUrl: String? = null

    private fun eval(js: String) {
        runOnUiThread { runCatching { web?.evaluateJavascript(js, null) } }
    }

    /** 安全地调用页面里的 startStream(url) */
    private fun playInPage(url: String) {
        if (!pageReady) {
            pendingUrl = url
            return
        }
        eval("startStream(${JSONObject.quote(url)})")
    }

    /** JS 回调统一入口（注意：在 WebView 的 JS 线程） */
    private inner class Bridge {
        @JavascriptInterface
        fun onEvent(type: String, msg: String) {
            Log.i(TAG, "js $type $msg")
            ui.post { handleEvent(type, msg) }
        }
    }

    private fun handleEvent(type: String, msg: String) {
        when (type) {
            "ready" -> Log.i(TAG, "jessibuca ready")

            "playing" -> {
                playing = true
                playStartAt = System.currentTimeMillis()
                playedSeconds = 0
                overlay.visibility = View.GONE
                btnRetry.visibility = View.GONE
                status.text = "播放中"
            }

            "info" -> {
                if (msg.isNotBlank() && msg != "0x0") resolution.text = "$msg · WASM 软解"
            }

            "stats" -> {
                if (playing) {
                    playedSeconds = ((System.currentTimeMillis() - playStartAt) / 1000).toInt()
                }
                val t = runCatching { JSONObject(msg) }.getOrNull()
                if (t != null) {
                    val fps = t.optInt("fps", 0)
                    val speed = t.optInt("speed", 0)
                    stats.text = "已播 ${playedSeconds}s · ${speed} KB/s · fps=$fps"
                } else {
                    stats.text = "已播 ${playedSeconds}s"
                }
            }

            "error", "timeout" -> {
                if (playing) {
                    // 已经在放、偶发错误就让它自己重连，不急着换地址
                    Log.w(TAG, "transient $type: $msg")
                    return
                }
                onStatus(if (type == "timeout") "拉流超时，尝试下一档清晰度…" else "播放出错：$msg")
                playNext()
            }
        }
    }

    // ─────────── 取流地址 ───────────

    private fun fetchAndPlay() {
        if (store.token == null) { onStatus("未设置令牌"); return }
        if (web == null) return
        playing = false
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

    private fun playNext() {
        if (web == null) return
        if (urlIndex >= urls.size) {
            playing = false
            onStatus("播放失败：所有清晰度都无法播放")
            overlay.visibility = View.VISIBLE
            btnRetry.visibility = View.VISIBLE
            return
        }
        val url = urls[urlIndex++]
        Log.i(TAG, "play[$urlIndex/${urls.size}] ${url.substringBefore('?')}")
        onStatus("连接中…（第 $urlIndex/${urls.size} 档）")
        playInPage(url)
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

    private fun onStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    override fun onDestroy() {
        playStartAt = 0
        runCatching { web?.evaluateJavascript("stopStream()", null) }
        runCatching {
            web?.let {
                videoBox.removeView(it)
                it.removeJavascriptInterface("Native")
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
        web = null
        im?.close()
        scope.cancel()
        super.onDestroy()
    }
}
