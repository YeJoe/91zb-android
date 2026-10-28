package com.jjlive.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * 播放页。
 *
 * 主链路：自研 FlvLivePlayer —— 自己解 FLV，MediaCodec 硬解 HEVC，零拷贝直出 SurfaceView。
 * 这与原版 App 的 `hardWareDecode` 路径一致，CPU 占用个位数、延迟 300ms 量级。
 *
 * 关键点：源站把视频 NALU tag 做了差分 XOR 混淆（首字节 = codecId+1，I 帧表现为 0x1d），
 * 还原逻辑在 FlvLivePlayer.deobfuscate()。不还原就会黑屏 —— 这正是之前所有解码方案失败的原因。
 *
 * 兜底：万一设备没有 HEVC 解码器（或被 CDN 换了别的封装），自动降级到 WebView + jessibuca(WASM)。
 *
 * 交互：上下滑动切换直播间（切间时整层跟手位移，松手超过阈值就换房间）。
 * 只有底部聊天区，没有飘屏弹幕；聊天内容经 ChatFilter 过滤掉进房/中奖/下注等营销消息。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/web/player.html"

        /** 原生链路多少毫秒还没出第一帧，就认为这条路走不通。 */
        private const val FIRST_FRAME_TIMEOUT = 8000L

        fun start(ctx: Context, rooms: List<Room>, index: Int) {
            val ids = ArrayList(rooms.map { it.roomId })
            val names = ArrayList(rooms.map { it.userName })
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putStringArrayListExtra("ids", ids)
                    .putStringArrayListExtra("names", names)
                    .putExtra("index", index)
                    .putExtra("roomId", rooms.getOrNull(index)?.roomId ?: "")
                    .putExtra("name", rooms.getOrNull(index)?.userName ?: "")
            )
        }

        fun start(ctx: Context, room: Room) = start(ctx, listOf(room), 0)
    }

    private lateinit var store: TokenStore
    private lateinit var api: Api
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var page: FrameLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var chat: RecyclerView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var resolution: TextView
    private lateinit var filterCount: TextView
    private lateinit var swipeHint: TextView
    private lateinit var overlay: View
    private lateinit var btnRetry: Button
    private lateinit var title: TextView

    private val chatItems = ArrayList<ChatMsg>()
    private lateinit var chatAdapter: ChatAdapter

    private var surfaceView: SurfaceView? = null
    private var player: FlvLivePlayer? = null
    private var web: WebView? = null
    private var im: ImSocket? = null

    /** 直播间列表（来自首页当前可见列表），用于上下滑动切换。 */
    private var ids: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var index = 0

    private var roomId = ""
    private var roomName = ""

    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    /** 请求序号：切房间后旧请求的回包直接丢弃，避免抢着起播。 */
    private var reqSeq = 0

    private var surfaceReady = false
    private var pendingPlay = false
    private var useWeb = false
    private var playing = false
    private var playedSeconds = 0
    private var playStartAt = 0L
    private var pageReady = false

    /** 过滤统计：收到多少帧、真正显示了多少条。 */
    private var imFrames = 0
    private var imShown = 0
    private var lastMsgAt = 0L

    // 手势
    private var downY = 0f
    private var downX = 0f
    private var downAt = 0L
    private var dragDy = 0f
    private var dragging = false
    private var switching = false
    private var chatTake = false
    private var chatDownY = 0f
    private var chatDownX = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val switchThreshold by lazy { 76f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        store = TokenStore(this)
        api = Api(store)

        ids = intent.getStringArrayListExtra("ids") ?: emptyList()
        names = intent.getStringArrayListExtra("names") ?: emptyList()
        roomId = intent.getStringExtra("roomId") ?: ""
        roomName = intent.getStringExtra("name") ?: ""
        index = intent.getIntExtra("index", 0).coerceAtLeast(0)
        // 传进来的 index 和 roomId 对不上（外部直接启动）时，按 roomId 反查
        if (ids.getOrNull(index) != roomId) {
            val at = ids.indexOf(roomId)
            if (at >= 0) index = at
        }
        if (index !in names.indices) index = 0

        page = findViewById(R.id.page)
        videoBox = findViewById(R.id.videoBox)
        chat = findViewById(R.id.chat)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        resolution = findViewById(R.id.resolution)
        filterCount = findViewById(R.id.filterCount)
        swipeHint = findViewById(R.id.swipeHint)
        overlay = findViewById(R.id.overlay)
        btnRetry = findViewById(R.id.btnRetry)
        title = findViewById(R.id.title)

        // 聊天区：从下往上堆
        chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatAdapter = ChatAdapter(chatItems)
        chat.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 120
        }
        chat.adapter = chatAdapter
        setupChatGesture()

        title.text = roomName

        btnRetry.setOnClickListener { fetchAndPlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        page.setOnTouchListener { _, ev -> onPageTouch(ev) }

        applyInsets()
        setupSurface()
        fetchAndPlay()
    }

    /** 沉浸式下避开状态栏 / 导航栏，避免标题和关闭按钮被系统图标压住。 */
    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(14), sys.top + dp(8), dp(14), dp(10))
            bottomBar.setPadding(dp(14), dp(6), dp(14), sys.bottom + dp(10))
            (chat.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(52) + sys.bottom
            chat.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(page)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─────────── 上下滑动切换直播间 ───────────

    private fun onPageTouch(ev: MotionEvent): Boolean {
        if (switching) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downX = ev.rawX
                downAt = System.currentTimeMillis()
                dragDy = 0f
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (page.height == 0) return true
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.3f) {
                    dragging = true
                    swipeHint.visibility = View.GONE
                }
                if (dragging) {
                    dragDy = dy
                    // 阻尼跟手：只走 55%，边界外再压一半
                    val edge = (index == 0 && dy > 0) || (index == ids.lastIndex && dy < 0)
                    page.translationY = dy * if (edge) 0.22f else 0.55f
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                finishDrag()
                return true
            }
        }
        return false
    }

    /** 松手：位移够远或速度够快就切间，否则回弹。 */
    private fun finishDrag() {
        val fast = System.currentTimeMillis() - downAt < 240 && abs(dragDy) > switchThreshold * 0.4f
        val far = abs(dragDy) > switchThreshold
        when {
            dragDy < 0 && (far || fast) -> switchRoom(1)
            dragDy > 0 && (far || fast) -> switchRoom(-1)
            else -> page.animate().translationY(0f).setDuration(160).start()
        }
    }

    /**
     * 聊天区也能上下切间：列表还能继续滚时让 RecyclerView 自己滚，
     * 已经滚到头了才把手势交给页面（这也是抖音一类直播间的做法）。
     */
    private fun setupChatGesture() {
        chat.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (switching) return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        chatTake = false
                        chatDownY = e.rawY
                        chatDownX = e.rawX
                        downY = e.rawY
                        downX = e.rawX
                        downAt = System.currentTimeMillis()
                        dragDy = 0f
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - chatDownY
                        val dx = e.rawX - chatDownX
                        if (!chatTake && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.3f) {
                            val canStillScroll =
                                if (dy < 0) rv.canScrollVertically(1) else rv.canScrollVertically(-1)
                            if (!canStillScroll) {
                                chatTake = true
                                swipeHint.visibility = View.GONE
                            }
                        }
                        if (chatTake) {
                            dragDy = dy
                            page.translationY = dy * 0.55f
                            return true
                        }
                        return false
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (chatTake) {
                            chatTake = false
                            finishDrag()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    /** @param delta +1 下一个（内容向上滑出），-1 上一个 */
    private fun switchRoom(delta: Int) {
        val next = index + delta
        if (ids.isEmpty() || next !in ids.indices) {
            page.animate().translationY(0f).setDuration(160).start()
            Toast.makeText(this, if (delta > 0) "已经是最后一个直播间" else "已经是第一个直播间", Toast.LENGTH_SHORT).show()
            return
        }

        switching = true
        index = next
        roomId = ids[next]
        roomName = names.getOrElse(next) { "" }

        val h = page.height.coerceAtLeast(1).toFloat()
        val outY = if (delta > 0) -h * 0.45f else h * 0.45f
        page.animate()
            .translationY(outY)
            .alpha(0.1f)
            .setDuration(150)
            .withEndAction {
                // 新一屏从另一侧滑入
                page.translationY = -outY
                resetForRoom()
                fetchAndPlay()
                page.animate().translationY(0f).alpha(1f).setDuration(220).start()
            }
            .start()
        // 动画被打断时也要解锁手势，别让页面卡在“切换中”
        ui.postDelayed({ switching = false }, 480)
    }

    /** 切房间时把上一条流、聊天、IM 全部清干净。 */
    private fun resetForRoom() {
        reqSeq++              // 让上一个房间的接口回包失效
        ui.removeCallbacks(firstFrameWatchdog)
        stopNative()
        runCatching { web?.evaluateJavascript("stopStream()", null) }

        playing = false
        playedSeconds = 0
        playStartAt = 0

        im?.close()
        im = null
        imFrames = 0
        imShown = 0
        chatAdapter.clear()
        renderFilterCount()

        title.text = roomName
        resolution.text = ""
        stats.text = ""
        overlay.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        onStatus("正在获取播放地址…")
    }

    // ─────────── 原生解码：SurfaceView + MediaCodec ───────────

    private fun setupSurface() {
        val sv = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    Log.i(TAG, "surface created")
                    surfaceReady = true
                    if (pendingPlay) {
                        pendingPlay = false
                        playNext()
                    }
                }

                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    Log.i(TAG, "surface destroyed")
                    surfaceReady = false
                    player?.stop()
                    player = null
                }
            })
        }
        surfaceView = sv
        videoBox.addView(sv)
    }

    private fun startNative(url: String) {
        val sv = surfaceView ?: return
        player?.stop()
        val p = FlvLivePlayer(sv.holder.surface)
        // 切房间的瞬间，旧播放器的回调可能还在路上；用实例比对把它们全部作废，
        // 否则旧流一句 onFinished 就会把新房间顶进 WebView 兜底。
        p.listener = object : FlvLivePlayer.Listener {
            override fun onStatus(msg: String) {
                if (player !== p) return
                ui.post { if (player === p && !isFinishing) status.text = msg }
            }

            override fun onVideoSize(w: Int, h: Int) {
                ui.post {
                    if (player !== p || isFinishing) return@post
                    fitVideo(w, h)
                    val codec = p.codecLabel.ifBlank { "MediaCodec" }
                    resolution.text = "$w×$h · $codec"
                    Log.i(TAG, "video size ${w}x$h $codec")
                }
            }

            override fun onStats(bytes: Long, seconds: Int, kbps: Int) {
                ui.post {
                    if (player !== p || isFinishing) return@post
                    playedSeconds = seconds
                    stats.text = "已播 ${seconds}s · ${kbps} KB/s"
                }
            }

            override fun onError(msg: String) {
                Log.w(TAG, "player error: $msg")
                if (player !== p) return
                if (msg == "CODEC_FAIL") ui.post { if (player === p) playNext() }
            }

            override fun onFinished() {
                ui.post { if (player === p && !isFinishing) playNext() }
            }

            override fun onFirstFrame() {
                ui.post {
                    if (player !== p || isFinishing) return@post
                    playing = true
                    playStartAt = System.currentTimeMillis()
                    overlay.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    status.text = "播放中 · 硬解"
                    ui.removeCallbacks(firstFrameWatchdog)
                    showSwipeHintOnce()
                }
            }
        }
        player = p
        playing = false
        p.start(url)

        // 迟迟不出画就换下一档；三档都不行再切 WebView 软解
        ui.removeCallbacks(firstFrameWatchdog)
        ui.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT)
    }

    private val firstFrameWatchdog = Runnable {
        if (!playing && !useWeb) {
            Log.w(TAG, "no frame after ${FIRST_FRAME_TIMEOUT}ms, next")
            playNext()
        }
    }

    private var hintShown = false

    private fun showSwipeHintOnce() {
        if (hintShown || ids.size <= 1) return
        hintShown = true
        swipeHint.alpha = 0f
        swipeHint.visibility = View.VISIBLE
        swipeHint.animate().alpha(1f).setDuration(260).withEndAction {
            ui.postDelayed({
                if (isFinishing) return@postDelayed
                swipeHint.animate().alpha(0f).setDuration(420).withEndAction {
                    swipeHint.visibility = View.GONE
                }.start()
            }, 2200)
        }.start()
    }

    /** 按视频宽高比铺满（不裁切），居中显示。SurfaceView 默认会把画面拉伸。 */
    private fun fitVideo(vw: Int, vh: Int) {
        val sv = surfaceView ?: return
        val bw = videoBox.width
        val bh = videoBox.height
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return
        val scale = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val lp = FrameLayout.LayoutParams((vw * scale).toInt(), (vh * scale).toInt())
        lp.gravity = android.view.Gravity.CENTER
        sv.layoutParams = lp
    }

    private fun stopNative() {
        ui.removeCallbacks(firstFrameWatchdog)
        player?.stop()
        player = null
        playing = false
    }

    /** 原生链路彻底失败 → 换成 WebView + jessibuca(WASM) 软解。 */
    private fun switchToWeb(reason: String) {
        if (useWeb) {
            onStatus("播放失败：$reason")
            overlay.visibility = View.VISIBLE
            btnRetry.visibility = View.VISIBLE
            return
        }
        Log.w(TAG, "fallback to WebView: $reason")
        useWeb = true
        onStatus("硬解不可用，切换软解…")
        stopNative()
        surfaceView?.let { videoBox.removeView(it) }
        surfaceView = null
        setupWebView()
        urlIndex = 0
        playNext()
    }

    // ─────────── WebView + jessibuca（兜底） ───────────

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
            ui.post { if (!isFinishing) handleEvent(type, msg) }
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
                status.text = "播放中 · 软解"
                showSwipeHintOnce()
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
        val my = ++reqSeq
        playing = false
        overlay.visibility = View.VISIBLE
        onStatus("正在获取播放地址…")
        urls = emptyList()
        urlIndex = 0

        scope.launch {
            val detail = withContext(Dispatchers.IO) {
                runCatching { api.call("/app/live/joinRoom", mapOf("liveId" to roomId)) }.getOrNull()
            }
            if (my != reqSeq || isFinishing) return@launch   // 期间又切了房间

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
        // 地址还没取回来（刚切房间/刚进页面）时收到旧回调，直接忽略，
        // 否则会把 urlIndex 推满并误判成“所有清晰度都失败”而切去软解。
        if (urls.isEmpty()) return
        if (urlIndex >= urls.size) {
            switchToWeb("所有清晰度都无法播放")
            return
        }
        val url = urls[urlIndex++]
        Log.i(TAG, "play[$urlIndex/${urls.size}] ${url.substringBefore('?')}")
        onStatus("连接中…（第 $urlIndex/${urls.size} 档）")
        btnRetry.visibility = View.GONE
        if (useWeb) {
            playInPage(url)
        } else {
            if (!surfaceReady) { pendingPlay = true; return }
            startNative(url)
        }
        if (im == null) startIm()
    }

    // ─────────── 聊天（IM） ───────────

    private fun startIm() {
        val token = store.token ?: return
        im?.close()
        im = ImSocket(token, store.deviceUid).also { s ->
            s.listener = object : ImSocket.Listener {
                override fun onOpen() {
                    Log.i(TAG, "im open")
                }

                override fun onClosed(reason: String) {
                    Log.w(TAG, "im closed: $reason")
                }

                override fun onMessage(text: String) {
                    handleIm(text)
                }
            }
            s.connect(roomId)
        }
    }

    /**
     * IM 帧 → 底部聊天区。
     * 解析与过滤全在 ChatFilter：只留真正的聊天正文，进房/中奖/下注/引流一律丢弃。
     */
    private fun handleIm(raw: String) {
        imFrames++
        val msg = ChatFilter.parse(raw)
        if (msg == null) {
            if (imFrames % 20 == 0) ui.post { if (!isFinishing) renderFilterCount() }
            Log.d(TAG, "im filtered << " + raw.replace("\n", " ").take(120))
            return
        }

        ui.post {
            if (isFinishing) return@post
            // 同一个人连续刷同一句话，6 秒内只留一条
            val now = System.currentTimeMillis()
            val prev = chatAdapter.last()
            if (prev != null && prev.nick == msg.nick && prev.body == msg.body && now - lastMsgAt < 6000) {
                renderFilterCount()
                return@post
            }
            lastMsgAt = now
            imShown++
            chatAdapter.push(msg)
            chat.scrollToPosition(chatAdapter.itemCount - 1)
            renderFilterCount()
        }
    }

    private fun renderFilterCount() {
        val hidden = (imFrames - imShown).coerceAtLeast(0)
        filterCount.text = if (hidden > 0) "已屏蔽 $hidden 条" else ""
    }

    private fun onStatus(msg: String) {
        runOnUiThread { if (!isFinishing) status.text = msg }
    }

    override fun onPause() {
        super.onPause()
        // WebView 软解很吃 CPU，页面不可见时暂停；原生解码保持拉流（回前台立刻有画面）
        if (useWeb) runCatching { web?.onPause() }
    }

    override fun onResume() {
        super.onResume()
        if (useWeb) runCatching { web?.onResume() }
    }

    override fun onDestroy() {
        playStartAt = 0
        stopNative()
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
