package com.jjlive.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.PictureInPictureParams
import android.os.Build
import android.content.pm.PackageManager
import android.util.Rational
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import androidx.core.content.ContextCompat
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
import kotlin.math.max
import kotlin.math.min

/**
 * 播放页。
 *
 * 主链路：自研 FlvLivePlayer —— 自己解 FLV，MediaCodec 硬解 HEVC，零拷贝直出 SurfaceView。
 * 这与原版 App 的 `hardWareDecode` 路径一致，CPU 占用个位数、延迟 300ms 量级。
 *
 * 关键点：源站把视频 NALU tag 做了差分 XOR 混淆（首字节 = codecId+1，I 帧表现为 0x1d），
 * 还原逻辑在 FlvLivePlayer.deobfuscate()。不还原就会黑屏。
 *
 * 兜底：设备没有 HEVC 解码器（或被 CDN 换了封装）时自动降级到 WebView + jessibuca(WASM)。
 *
 * 交互：
 *  · 默认「半屏」——上方画面、下方深色聊天区；点画面或「全屏」按钮放大到竖屏全屏，再点回来；
 *  · 上下滑动切直播间：手势由 SwipeLayout 在父容器层面拦截，落点在聊天区也能切；
 *  · 只有底部聊天区，没有飘屏弹幕，聊天内容经 ChatFilter 过滤营销消息。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/web/player.html"

        /** 原生链路多少毫秒还没出第一帧，就认为这条路走不通。 */
        private const val FIRST_FRAME_TIMEOUT = 8000L

        /** 半屏形态下画面占屏幕高度的比例。 */
        private const val HALF_RATIO = 0.46f

        /** 切间判定：位移超过屏高的这个比例，或者速度达到 FLING。 */
        private const val SWITCH_RATIO = 0.14f

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

    private lateinit var page: SwipeLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var chatPanel: SlideAwayLayout
    private lateinit var btnChat: TextView
    private lateinit var btnFollow: TextView
    private lateinit var btnPip: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var sendBar: View
    private lateinit var etDanmu: EditText
    private lateinit var btnSend: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var scrimTop: View
    private lateinit var chat: RecyclerView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var resolution: TextView
    private lateinit var filterCount: TextView
    private lateinit var btnMode: TextView
    private lateinit var swipeHint: TextView
    private lateinit var nextHint: TextView
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
    private var roomUserId = ""
    private var isFollowing = false
    private var sendBarH = 0

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

    /** 是否处于全屏形态。 */
    private var fullScreen = false

    /** 聊天区是否被右滑甩走了（此时画面铺满，只留一个"聊天"胶囊）。 */
    private var chatHidden = false
    /** 甩走之前的形态，叫回聊天区时恢复。 */
    private var modeBeforeHide = false
    private var barHeight = 0
    private var insetBottom = 0
    private var lastVw = 0
    private var lastVh = 0
    private var fitting = false

    private var switching = false
    private var fitPosted = false

    /** 过滤统计：收到多少帧、真正显示了多少条。 */
    private var imFrames = 0
    private var imShown = 0
    private var lastMsgAt = 0L

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
        chatPanel = findViewById(R.id.chatPanel)
        bottomBar = findViewById(R.id.bottomBar)
        scrimTop = findViewById(R.id.scrimTop)
        chat = findViewById(R.id.chat)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        resolution = findViewById(R.id.resolution)
        filterCount = findViewById(R.id.filterCount)
        btnMode = findViewById(R.id.btnMode)
        swipeHint = findViewById(R.id.swipeHint)
        nextHint = findViewById(R.id.nextHint)
        overlay = findViewById(R.id.overlay)
        btnRetry = findViewById(R.id.btnRetry)
        title = findViewById(R.id.title)
        btnChat = findViewById(R.id.btnChat)
        btnFollow = findViewById(R.id.btnFollow)
        btnPip = findViewById(R.id.btnPip)
        btnRefresh = findViewById(R.id.btnRefresh)
        sendBar = findViewById(R.id.sendBar)
        etDanmu = findViewById(R.id.etDanmu)
        btnSend = findViewById(R.id.btnSend)

        // 聊天区：从下往上堆
        chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatAdapter = ChatAdapter(chatItems)
        chat.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 120
        }
        chat.adapter = chatAdapter

        setupSwipe()

        title.text = roomName
        btnRetry.setOnClickListener { fetchAndPlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        btnMode.setOnClickListener { toggleMode() }
        btnChat.setOnClickListener { showChat() }
        btnFollow.setOnClickListener { doFollow() }
        btnPip.setOnClickListener { enterPip() }
        btnRefresh.setOnClickListener { fetchAndPlay() }
        setupDanmu()

        // 默认全屏（按需求取消半屏展示）
        fullScreen = true
        sendBarH = dp(48)
        setupChatSwipe()

        applyInsets()
        page.post { applyMode() }
        setupSurface()
        fetchAndPlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 打了 configChanges，旋转不会重建页面，得自己重排一次
        page.post { applyMode() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 沉浸式下避开状态栏 / 导航栏，避免标题和关闭按钮被系统图标压住。 */
    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetBottom = sys.bottom
            topBar.setPadding(dp(14), sys.top + dp(8), dp(14), dp(10))
            barHeight = dp(40) + sys.bottom
            bottomBar.layoutParams = bottomBar.layoutParams.apply { height = barHeight }
            bottomBar.setPadding(dp(14), 0, dp(14), sys.bottom)
            val sp = sendBar.layoutParams as FrameLayout.LayoutParams
            sp.bottomMargin = barHeight
            sendBar.layoutParams = sp
            applyMode()
            insets
        }
        ViewCompat.requestApplyInsets(page)
    }

    // ─────────── 半屏 / 全屏 ───────────

    /**
     * 半屏：画面占上半屏，聊天区是深色面板铺在下面（面板上沿有圆角，扣在画面上）。
     * 全屏：画面铺满，聊天区变成压底的渐隐层。
     */
    private fun applyMode() {
        val h = page.height
        if (h <= 0) {
            if (!fitPosted) {
                fitPosted = true
                page.post { fitPosted = false; applyMode() }
            }
            return
        }
        swipeHint.visibility = View.GONE

        val lp = videoBox.layoutParams as FrameLayout.LayoutParams
        val cp = chatPanel.layoutParams as FrameLayout.LayoutParams
        val barH = if (barHeight > 0) barHeight else dp(40)

        // 聊天区被右滑收起：不再摆面板，画面直接铺满，只留一个"聊天"胶囊
        if (chatHidden) {
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            videoBox.layoutParams = lp
            chatPanel.visibility = View.GONE
            val bp = btnChat.layoutParams as FrameLayout.LayoutParams
            if (bp.bottomMargin != barH + dp(8)) {
                bp.bottomMargin = barH + dp(8)
                btnChat.layoutParams = bp
            }
            btnChat.visibility = View.VISIBLE
            scrimTop.alpha = 1f
            videoBox.post { fitVideo(lastVw, lastVh) }
            return
        }
        chatPanel.visibility = View.VISIBLE
        btnChat.visibility = View.GONE

        // 始终全屏布局（已取消半屏展示）
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        cp.gravity = Gravity.BOTTOM
        cp.topMargin = 0
        cp.height = dp(248) + insetBottom
        cp.bottomMargin = barH + sendBarH
        chatPanel.setBackgroundResource(R.drawable.bg_chat_full)
        chat.setPadding(dp(16), dp(2), dp(16), dp(6))
        btnMode.text = if (chatHidden) "聊天" else "隐藏"
        scrimTop.alpha = 1f
        videoBox.layoutParams = lp
        chatPanel.layoutParams = cp

        // 切间提示摆在画面正中间
        val sp = swipeHint.layoutParams as FrameLayout.LayoutParams
        sp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        sp.topMargin = h / 2 - dp(18)
        swipeHint.layoutParams = sp

        videoBox.post { fitVideo(lastVw, lastVh) }
    }

    /** 全屏形态下，「隐藏/聊天」按钮用于收起或叫回聊天区。 */
    private fun toggleMode() {
        if (chatHidden) showChat() else hideChat()
    }

    // ─────────── 右滑收起聊天区 ───────────

    private fun setupChatSwipe() {
        chatPanel.onDismiss = { hideChat() }
    }

    /**
     * 右滑把聊天区甩走：画面铺满，底部留一个"聊天"胶囊随时叫回来。
     * 半屏时收起会顺带进入全屏（否则下方只剩黑底），全屏时只隐藏压底层。
     */
    private fun hideChat() {
        if (chatHidden) return
        chatHidden = true
        modeBeforeHide = fullScreen
        fullScreen = true
        applyMode()
        Log.i(TAG, "chat hidden")
    }

    /** 叫回聊天区，回到收起前的形态。 */
    private fun showChat() {
        if (!chatHidden) return
        chatHidden = false
        chatPanel.resetTransform()
        chatPanel.visibility = View.VISIBLE
        fullScreen = modeBeforeHide
        applyMode()
        chatPanel.translationX = dp(36).toFloat()
        chatPanel.alpha = 0f
        chatPanel.animate().alpha(1f).translationX(0f).setDuration(200).start()
    }

    // ─────────── 上下滑动切换直播间 ───────────

    private fun setupSwipe() {
        val r = Rect()
        page.extent = { page.height.coerceAtLeast(1).toFloat() }
        page.inScrollableArea = { x, y ->
            chatPanel.getHitRect(r)
            r.contains(x.toInt(), y.toInt())
        }
        // 聊天列表还能往这个方向滚，就把手势让给它
        page.childCanScroll = { dy ->
            if (dy < 0) chat.canScrollVertically(1) else chat.canScrollVertically(-1)
        }
        page.onDragStart = {
            swipeHint.animate().alpha(0f).setDuration(120).start()
        }
        page.onDrag = { dy, _ -> onDrag(dy) }
        page.onDragEnd = { dy, vy -> finishDrag(dy, vy) }
        page.onTap = { _, _ -> if (chatHidden) showChat() else toggleMode() }
    }

    private fun onDrag(dy: Float) {
        val h = page.height.coerceAtLeast(1)
        val atEdge = (index == 0 && dy > 0) || (index == ids.lastIndex && dy < 0)
        // 1:1 跟手（阻尼会让人觉得"滑不动"，这也是之前成功率低的原因之一），
        // 只有到边界时才加阻力，明确告诉用户没有下一间了
        val ty = (if (atEdge) dy * 0.2f else dy).coerceIn(-h * 1.1f, h * 1.1f)
        page.translationY = ty

        val p = (abs(ty) / h).coerceIn(0f, 1f)
        nextHint.translationY = (if (ty < 0) 1 else -1) * (h - abs(ty)) / 2f
        nextHint.alpha = if (atEdge) 0f else (p * 1.6f).coerceAtMost(1f)
        nextHint.text = if (ty < 0) "↓  松手进入下一个直播间" else "↑  松手返回上一个直播间"
    }

    private fun finishDrag(dy: Float, vy: Int) {
        if (switching) return
        val h = page.height.coerceAtLeast(1)
        val far = abs(dy) > h * SWITCH_RATIO
        val fast = abs(vy) > SwipeLayout.FLING && abs(dy) > dp(20)
        if (!far && !fast) {
            bounceBack()
            return
        }
        val delta = if (dy < 0) 1 else -1
        val next = index + delta
        if (ids.isEmpty() || next !in ids.indices) {
            bounceBack()
            Toast.makeText(
                this,
                if (delta > 0) "已经是最后一个直播间" else "已经是第一个直播间",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        switchRoom(delta)
    }

    private fun bounceBack() {
        nextHint.animate().alpha(0f).setDuration(120).start()
        page.animate()
            .translationY(0f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** @param delta +1 下一个（内容向上滑出），-1 上一个 */
    private fun switchRoom(delta: Int) {
        if (switching) return
        switching = true
        page.cancelDrag()
        page.dragEnabled = false

        index += delta
        roomId = ids[index]
        roomName = names.getOrElse(index) { "" }

        val h = page.height.coerceAtLeast(1).toFloat()
        val outY = if (delta > 0) -h else h
        page.animate()
            .translationY(outY)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction {
                // 新一屏从对面小幅落位（不是整屏推入），响应更干脆
                page.translationY = -outY * 0.16f
                resetForRoom()
                fetchAndPlay()
                nextHint.alpha = 0f
                // 用 post 起第二段：在同一个 ViewPropertyAnimator 的 end 回调里
                // 直接再 animate() 会跟它自己的状态机打架
                page.post {
                    page.animate()
                        .translationY(0f)
                        .setDuration(210)
                        .setInterpolator(DecelerateInterpolator(2.2f))
                        .withEndAction {
                            switching = false
                            page.dragEnabled = true
                        }
                        .start()
                }
            }
            .start()

        // 动画被系统打断（退后台、内存吃紧）时的兜底，别把页面锁在"切换中"
        ui.postDelayed({
            if (switching) {
                switching = false
                page.dragEnabled = true
                page.translationY = 0f
            }
        }, 900)
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

        // 换房间时把聊天区叫回来（不然新房间一进来就是"没聊天"的状态）
        if (chatHidden) {
            chatHidden = false
            fullScreen = true
            chatPanel.resetTransform()
            applyMode()
        }

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
        swipeHint.visibility = View.VISIBLE
        swipeHint.alpha = 0f
        swipeHint.animate().alpha(1f).setDuration(260).withEndAction {
            ui.postDelayed({
                if (isFinishing) return@postDelayed
                swipeHint.animate().alpha(0f).setDuration(420).withEndAction {
                    swipeHint.visibility = View.GONE
                }.start()
            }, 2200)
        }.start()
    }

    /**
     * 按视频宽高比适配画面。
     *
     * 全屏用 contain（完整画面，留黑边）；半屏用 cover（填满画面框，像视频卡片），
     * 但裁切过多时退回 contain，免得把画面裁得只剩一条。
     */
    private fun fitVideo(vw: Int, vh: Int) {
        if (vw > 0 && vh > 0) {
            lastVw = vw
            lastVh = vh
        }
        val w0 = lastVw
        val h0 = lastVh
        val sv = surfaceView ?: return
        if (w0 <= 0 || h0 <= 0) return

        val bw = videoBox.width
        val bh = videoBox.height
        if (bw <= 0 || bh <= 0) {
            if (!fitting) {
                fitting = true
                videoBox.post {
                    fitting = false
                    fitVideo(0, 0)
                }
            }
            return
        }

        val sx = bw.toFloat() / w0
        val sy = bh.toFloat() / h0
        var scale = if (fullScreen) min(sx, sy) else max(sx, sy)
        if (!fullScreen) {
            // 裁切后还剩多少可见面积，低于 45% 就别硬裁了
            val visible = min(1f, bw / (w0 * scale)) * min(1f, bh / (h0 * scale))
            if (visible < 0.45f) scale = min(sx, sy)
        }
        val lp = FrameLayout.LayoutParams((w0 * scale).toInt(), (h0 * scale).toInt())
        lp.gravity = Gravity.CENTER
        if (sv.layoutParams.width != lp.width || sv.layoutParams.height != lp.height) {
            sv.layoutParams = lp
        }
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
            roomUserId = room?.userId ?: ""
            isFollowing = false
            ui.post { updateFollowBtn() }

            // 默认原画 → 720P → 360P；「设置 - 优先清晰度」可改成 720 或省流优先
            val u0 = room?.url0.orEmpty()
            val u720 = room?.url720.orEmpty()
            val u360 = room?.url360.orEmpty()
            urls = when (store.quality) {
                1 -> listOf(u720, u0, u360)
                2 -> listOf(u360, u720, u0)
                else -> listOf(u0, u720, u360)
            }.filter { it.isNotBlank() }.distinct()

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
        im?.close()
        im = ImSocket(store.token, store.deviceUid, host = store.imHost).also { s ->
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

    // ─────────── 关注（真实接口） ───────────

    /** 刷新顶部"关注/已关注"按钮文案与配色。 */
    private fun updateFollowBtn() {
        if (roomUserId.isBlank()) {
            btnFollow.visibility = View.GONE
            return
        }
        btnFollow.visibility = View.VISIBLE
        if (isFollowing) {
            btnFollow.text = "已关注"
            btnFollow.setBackgroundResource(R.drawable.pill_dark)
            btnFollow.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            btnFollow.text = "关注"
            btnFollow.setBackgroundResource(R.drawable.bg_btn_accent)
            btnFollow.setTextColor(ContextCompat.getColor(this, R.color.accent_ink))
        }
    }

    /** 点击关注/已关注：真实调用 /user/attention/add|cancel。 */
    private fun doFollow() {
        if (roomUserId.isBlank()) return
        val target = !isFollowing
        btnFollow.isEnabled = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) { api.follow(roomUserId, target) }
            ui.post {
                btnFollow.isEnabled = true
                if (ok) {
                    isFollowing = target
                    updateFollowBtn()
                    Toast.makeText(
                        this@PlayerActivity,
                        if (isFollowing) "已关注主播" else "已取消关注",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@PlayerActivity, "操作失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────── 发送弹幕（真实接口） ───────────

    private fun setupDanmu() {
        btnSend.setOnClickListener { sendDanmu() }
        etDanmu.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendDanmu()
                true
            } else false
        }
    }

    private fun sendDanmu() {
        val msg = etDanmu.text?.toString()?.trim().orEmpty()
        if (msg.isEmpty()) return
        if (roomUserId.isBlank()) {
            Toast.makeText(this, "直播间信息未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val toSend = msg
        etDanmu.setText("")
        // 收起键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etDanmu.windowToken, 0)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { api.sendMsg(roomUserId, toSend) }
            if (!ok && !isFinishing) {
                ui.post {
                    Toast.makeText(this@PlayerActivity, "弹幕发送失败", Toast.LENGTH_SHORT).show()
                    etDanmu.setText(toSend)
                }
            }
            // 成功时弹幕会通过 IM 长连接广播回来（evt:5），聊天区自动显示，无需本地插入
        }
    }

    // ─────────── 画中画 ───────────

    /** 手动点击"画中画"按钮进入。 */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "当前系统不支持画中画（需 Android 8.0+）", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "设备不支持画中画", Toast.LENGTH_SHORT).show()
            return
        }
        val w = if (lastVw > 0) lastVw else 16
        val h = if (lastVh > 0) lastVh else 9
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 切到后台时自动进入画中画，继续看直播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playing) {
            runCatching { enterPip() }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        val topBar = findViewById<View>(R.id.topBar)
        if (isInPictureInPictureMode) {
            // 进入画中画：隐藏所有 UI 浮层，只留画面
            topBar.visibility = View.GONE
            chatPanel.visibility = View.GONE
            bottomBar.visibility = View.GONE
            sendBar.visibility = View.GONE
            btnChat.visibility = View.GONE
            swipeHint.visibility = View.GONE
        } else {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            sendBar.visibility = View.VISIBLE
            applyMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开页面就撤掉常亮，别在后台白白耗电
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // WebView 软解很吃 CPU，页面不可见时暂停；原生解码保持拉流（回前台立刻有画面）
        if (useWeb) runCatching { web?.onPause() }
    }

    override fun onResume() {
        super.onResume()
        // 直播页常亮：看直播时手不会一直摸屏幕，不常亮几十秒就息屏了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
