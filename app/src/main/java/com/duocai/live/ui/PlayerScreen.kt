package com.tm.live.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tm.live.AppContainer
import com.tm.live.MainActivity
import com.tm.live.R
import com.tm.live.data.DmMessage
import com.tm.live.data.Room
import com.tm.live.data.StreamMode
import com.tm.live.player.PlayerHolder
import com.tm.live.ui.theme.Gold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** 聊天面板的临时状态：背景不透明度 */
class ChatState {
    var bg by mutableFloatStateOf(0.62f)
}

/**
 * 播放页的可变 UI 状态。
 *
 * 为什么要有这个类：播放页一次塞十几个 remember { mutableStateOf() } 加两个内联手势
 * lambda，Kotlin 生成的方法体大到 D8 产出 ART 校验器不接受的字节码，表现为
 * 「点进直播间直接闪退」：
 *
 *     java.lang.VerifyError: Verifier rejected class PlayerScreenKt
 *       [0x14E] copy1 v2<-v263 type=Reference: Composer
 *
 * 把状态收进一个普通类，主 Composable 里就只剩一个局部变量，寄存器压力立刻降下来。
 */
private class PlayerUi {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var chrome by mutableStateOf(true)
    var scaleMode by mutableStateOf(0)
    var chatHint by mutableStateOf(false)
    var swipeHint by mutableStateOf(false)
    var tapJob: Job? = null

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** 捏合 / 平移；sw、sh 是手势层尺寸，用来夹住平移范围 */
    fun applyPinch(factor: Float, dx: Float, dy: Float, sw: Float, sh: Float) {
        val next = (zoom * factor).coerceIn(1f, 3f)
        zoom = next
        if (next <= 1.02f) {
            panX = 0f
            panY = 0f
        } else {
            val maxX = sw * (next - 1f) / 2f
            val maxY = sh * (next - 1f) / 2f
            panX = (panX + dx).coerceIn(-maxX, maxX)
            panY = (panY + dy).coerceIn(-maxY, maxY)
        }
    }
}

/**
 * 上下滑动切间的状态机（对标 91zb 的 SwipeLayout）。
 *
 * 之前用 Compose 的 VerticalPager + 盖在上面的全屏 GestureLayer：手势层不消费单指
 * 事件、理论上 Pager 还能滚，但在 OnePlus Android 15 上实测竖直滑动被吃掉，怎么都滑不动。
 * 这里干脆不用 Pager —— 直接自己记「当前是第几间」和「整页竖直位移」，跟手 1:1 平移，
 * 松手后按位移/速度决定回弹还是切间。和源 App 的做法完全一致，也不再依赖任何内部
 * scrollable 的手势仲裁。
 */
private class PlayerPager(initialIndex: Int, private val count: Int) {
    private var indexState by mutableIntStateOf(initialIndex)
    var index: Int
        get() = indexState
        set(v) {
            indexState = v
        }

    /**
     * 整页竖直位移（px），正 = 往下拖。
     *
     * 这里刻意用普通 Float 状态，而不是 `Animatable`：`Animatable.snapTo` 是挂起函数，
     * 而拖动回调跑在 `awaitPointerEventScope` 这个**受限**协程作用域里 —— 那里不允许
     * 调用外部挂起函数，否则编译期直接报
     * 「Restricted suspending functions can invoke … only on their restricted coroutine scope」。
     * 拖动本身只需要同步赋值，补间动画交给 [finish] / [cancelDrag] 里的协程做。
     */
    var offset by mutableFloatStateOf(0f)

    /** 一屏高度（px），onSizeChanged 时写入 */
    var pageHeight by mutableFloatStateOf(1f)

    private var busy = false

    /** 0~1，拖了多少（控制栏跟着淡出用） */
    fun progress(): Float = (abs(offset) / pageHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** 拖动中：1:1 跟手；到边界加 0.2 阻力，明确告诉用户没有下一间了 */
    fun drag(dy: Float) {
        if (busy) return
        val h = pageHeight.coerceAtLeast(1f)
        val atEdge = (index == 0 && dy > 0) || (index == count - 1 && dy < 0)
        offset = (if (atEdge) dy * 0.2f else dy).coerceIn(-h * 1.1f, h * 1.1f)
    }

    /** 松手：位移过 14% 屏高或速度够快就切间，否则回弹。两段动画和源 App 一致 */
    fun finish(scope: CoroutineScope, dy: Float, vy: Float) {
        if (busy) return
        val h = pageHeight.coerceAtLeast(1f)
        val far = abs(dy) > h * 0.14f
        val fast = abs(vy) > 780f && abs(dy) > 20f
        val delta = if (dy < 0) 1 else -1
        val next = index + delta
        if ((!far && !fast) || next !in 0 until count) {
            scope.launch { animTo(0f, 190) }
            return
        }
        busy = true
        scope.launch {
            // 1) 旧屏整页滑出
            animTo(if (delta > 0) -h else h, 150)
            // 2) 换到新一间，新屏从对面小幅落位（不是整屏推入，更干脆）
            index = next
            offset = if (delta > 0) h * 0.16f else -h * 0.16f
            // 3) 落位
            animTo(0f, 210)
            busy = false
        }
    }

    /** 双指捏合打断了单指拖拽：把残余位移复位 */
    fun cancelDrag(scope: CoroutineScope) {
        if (!busy && abs(offset) > 0.5f) scope.launch { animTo(0f, 150) }
    }

    /**
     * 程序化切到第 n 间（自动跳过已下播房用）。
     *
     * 和手势切间不同，这里不做两段动画：用户根本没参与滑动，
     * 播个"滑出再落下"反而突兀，直接落位即可。
     */
    fun jumpTo(n: Int) {
        if (n !in 0 until count) return
        if (busy) return
        index = n
        offset = 0f
    }

    /** 把 [offset] 以 tween 推到目标值。只能在协程里调用（[animate] 是挂起函数） */
    private suspend fun animTo(target: Float, ms: Int) {
        animate(offset, target, animationSpec = tween(ms)) { value, _ -> offset = value }
    }
}

/**
 * 播放页。
 *
 * 三条硬规矩：
 *  1. 全页只有一个 ExoPlayer，切房只换 MediaItem（多实例必 OOM）
 *  2. 视频和封面同处一个可竖直平移的「页面」里，上下滑时整页跟手走
 *  3. 弹幕区右滑变透明，文字加投影保证可读
 *
 * 本函数刻意保持"薄"：只算状态、摆层级，具体渲染全在下面几个私有 Composable 里。
 * 这不是风格问题，是 ART 校验器的硬约束（见 PlayerUi 注释）。
 */
@Composable
fun PlayerScreen(c: AppContainer, startIndex: Int, onClose: () -> Unit) {

    val rooms = c.playlist
    if (rooms.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val safeStart = startIndex.coerceIn(0, rooms.size - 1)
    val pager = remember { PlayerPager(safeStart, rooms.size) }
    val holder = c.player
    val context = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val ui = remember {
        PlayerUi().also {
            it.scaleMode = c.scaleMode
            it.chatHint = !c.prefs.sawChatSwipeHint
            it.swipeHint = !c.prefs.sawPlayerSwipeHint
        }
    }
    var entered by remember { mutableStateOf(false) }

    val activity = remember(context) { context.findActivity() }
    val host = activity as? MainActivity
    // inPip 的唯一真相源在容器里（MainActivity 同步），播放页和 RootScreen 的返回键都读它
    val inPip = c.inPip
    val pipAvailable = remember(host) { host?.pipSupported() == true }

    // 画中画参数提前设好。进小窗只能由用户点顶栏「浮窗」手动触发 ——
    // 退到桌面不自动缩窗（MainActivity 里 setAutoEnterEnabled(false) 已经把系统的自动进关掉）。
    LaunchedEffect(host, pipAvailable) {
        if (host != null && pipAvailable) host.syncPip()
    }

    // 关注写接口回执：点关注后后台把请求打到真实服务、复查 my_follow，结果回这里弹 toast
    //
    // 只在**用户真的点了关注**之后弹，不能进页面就弹。
    // 原来直接 LaunchedEffect(c.followSyncSeq) 有两个坑：
    //   1. Compose 首次组合时 effect 一定会跑一次，此时 seq 是历史值（非 0）、
    //      followSync 还残留着上一次的文案 → 进任意直播间立刻弹「服务端未确认」；
    //   2. followSync 从不清空，残留会一直存在，换个房间又弹一次。
    // 现在用「进页面时的 seq 基线」做门槛，并让容器在消费后清空。
    val seqBaseline = remember { mutableStateOf(c.followSyncSeq) }
    LaunchedEffect(c.followSyncSeq) {
        // 首次组合：把当前 seq 记为基线，不弹任何东西
        if (c.followSyncSeq == seqBaseline.value) return@LaunchedEffect
        seqBaseline.value = c.followSyncSeq
        c.followSync?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            c.consumeFollowSync()
        }
    }

    val index = pager.index
    val room = rooms.getOrNull(index) ?: rooms[safeStart]

    // 换房 → 换流；同一个地址不会重新握手
    LaunchedEffect(index) {
        val r = rooms.getOrNull(index) ?: return@LaunchedEffect
        ui.resetZoom()
        holder.open(
            r.streamUrls(c.prefs.rtmpDomain, StreamMode.of(c.streamMode)),
            c.autoPlay
        )
        // 弹幕跟着换房走：同一条隧道上重开 WS，打新房间的 /websock/join
        // （房间号 + 分区 id 一起给，之后只收这个房间的实时推送）
        c.danmaku.start(this, r)
    }

    // ---- 自动跳过播不了的房间 ----
    // 列表里的在播状态来自接口残留值，难免混着已下播的房：点进去停在错误页很烦。
    // 这里停 0.8 秒就自动换下一间；连续 8 间都播不了才真的停下 ——
    // 否则会一路空转把整个列表刷一遍，反而更糟。
    var autoSkip by remember { mutableIntStateOf(0) }
    val maxAutoSkip = 8

    // 只要成功起播，就把连败计数清零
    LaunchedEffect(holder.ready, holder.error) {
        if (holder.ready && holder.error == null) autoSkip = 0
    }

    // 报错（含「直播已结束」）→ 换下一间
    LaunchedEffect(index, holder.error) {
        if (holder.error == null) return@LaunchedEffect
        if (autoSkip >= maxAutoSkip) return@LaunchedEffect
        delay(800)
        autoSkip++
        val next = index + 1
        if (next < rooms.size) pager.jumpTo(next)
    }

    // 既没报错也迟迟不出画面（卡在缓冲）同样跳过：8 秒足够 FLV 起播了
    LaunchedEffect(index) {
        delay(8000)
        if (!holder.ready && holder.error == null && autoSkip < maxAutoSkip) {
            autoSkip++
            val next = index + 1
            if (next < rooms.size) pager.jumpTo(next)
        }
    }

    LaunchedEffect(Unit) {
        entered = true
        if (ui.chatHint) {
            delay(4200)
            ui.chatHint = false
            c.prefs.sawChatSwipeHint = true
        }
        if (ui.swipeHint) {
            delay(3200)
            ui.swipeHint = false
            c.prefs.sawPlayerSwipeHint = true
        }
    }

    // 看直播也别让屏幕自己灭 —— 只在这一页生效，退出立刻还回去
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            holder.pause()
            c.danmaku.stop()
        }
    }

    val enterAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(220), label = "enter")
    val videoAlpha by animateFloatAsState(
        if (holder.ready && holder.error == null) 1f else 0f,
        tween(260),
        label = "video"
    )
    val chromeSwitch by animateFloatAsState(if (ui.chrome) 1f else 0f, tween(200), label = "chrome")
    // 拖动进度（读 pager.progress()，会随位移重组，和之前的 Pager 一致）
    val dragProgress = pager.progress()
    val chromeAlpha = if (inPip) 0f else chromeSwitch * (1f - min(1f, dragProgress * 2.2f))

    // 缩放模式只剩「适应 / 拉伸」两种预设；任意倍率靠双指捏合（1×–3×）
    val resizeMode =
        if (ui.scaleMode == 2) AspectRatioFrameLayout.RESIZE_MODE_FILL
        else AspectRatioFrameLayout.RESIZE_MODE_FIT

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { pager.pageHeight = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer { alpha = enterAlpha }
    ) {
        // 可竖直平移的「页面」：视频 + 封面。滑动时整页跟手，松手后由 PlayerPager 决定切/回弹。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pager.offset }
        ) {
            // 小窗里不淡入：alpha < 1 会给这一层单独开离屏缓冲，模拟器/部分 ROM 上
            // 这层半透明缓冲会跟 SystemUI 的 PiP 菜单抢合成顺序，看着就像菜单上蒙了层阴影。
            // blockTouch=inPip：PiP 下播放器不接触摸，否则会吃掉底部「还原」的点击。
            VideoLayer(holder, ui, if (inPip) 1f else videoAlpha, resizeMode, blockTouch = inPip)
            // 封面盖在视频上：视频就绪后淡出，切房瞬间（ready=false）立刻显出来遮住换流的空档。
            // 小窗里整块摘掉 —— 尺寸太小（名字挤成一团），而且这层是全屏铺满的，
            // 少一层全屏绘制，PiP 菜单的按钮就少一层遮挡风险。
            if (!inPip) {
                CoverPage(room = rooms[index], coverAlpha = 1f - videoAlpha)
            }
        }

        // 手势层：单指上下滑切间 / 单指点击收显控制栏 / 双击暂停 / 双指缩放。
        // 放在页面之上、控制栏之下 —— 控制栏（按钮、聊天列表）在更上层先拿到事件，
        // 列表能滚就让列表滚，其余情况才由这一层接管竖直拖拽。
        //
        // 小窗里**整层不挂**：它是一块铺满全屏的 pointerInput。虽然 enabled=false 时
        // 不消费事件，但 PiP 的输入是 SystemUI 的 pip_input_consumer 统一接的，
        // 应用侧再压一层全屏手势节点，等于跟系统菜单抢同一次点击。
        // 小窗里唯一需要点的是底部「还原」，挂在最上层就够。
        if (!inPip) {
            PlayerGestureLayer(pager, ui, holder, enabled = true)
        }

        // 聊天面板的临时状态提到外面：面板会被整块摘掉（见下面 chromeUp），
        // 状态放在里面的话每次藏/显背景不透明度都会被重置回 62%
        val chatState = remember { ChatState() }

        if (!inPip) {
            // **只有面板真的可见时才挂上去**。
            // 之前是无论 alpha 多少都常驻：藏起来之后 TopBar / ChatPanel 仍然参与命中测试，
            // 点到下半屏（不可见的聊天区）或左上角（不可见的返回键）全被它们吃掉，
            // 手势层收不到，于是"面板一藏就再也点不回来"。
            val chromeUp = chromeAlpha > 0.02f
            if (chromeUp) {
                TopBar(
                    room = room,
                    c = c,
                    ui = ui,
                    alpha = chromeAlpha,
                    pipAvailable = pipAvailable,
                    onEnterPip = { host?.enterPip() },
                    onClose = onClose
                )
                Box(Modifier.align(Alignment.CenterEnd)) {
                    SideRail(
                        room = room,
                        c = c,
                        pageLabel = if (rooms.size > 1) "${index + 1}/${rooms.size}" else "",
                        alpha = chromeAlpha
                    )
                }
                if (ui.swipeHint) {
                    Box(Modifier.align(Alignment.TopCenter)) {
                        SwipeHint(onDismiss = {
                            ui.swipeHint = false
                            c.prefs.sawPlayerSwipeHint = true
                        })
                    }
                }
                ChatPanel(
                    c = c,
                    chat = chatState,
                    panelAlpha = chromeAlpha,
                    height = screenHeight * 0.40f,
                    hintVisible = ui.chatHint,
                    onDismissHint = {
                        ui.chatHint = false
                        c.prefs.sawChatSwipeHint = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // 输入框要能跟着键盘抬起来，否则打字时被输入法整个盖住
                        .imePadding()
                )
            }
        }

        // 小窗里自己画一个「还原」按钮。
        //
        // 位置放在**底部居中**，不是右上角 —— 这是实测踩出来的：
        // 鼠标悬停到小窗上时，SystemUI 会在小窗顶部铺一条工具条（齿轮 + 关闭 X），
        // 整块区域变成系统菜单的命中区。原来按钮放右上角，正好压在关闭按钮底下，
        // 点上去落到系统那层，看着就是「有层阴影挡着，点了没反应」。
        // 底部这块系统菜单不用，留给我们自己。
        if (inPip) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable { host?.exitPipToFull() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ExitToApp,
                    contentDescription = "还原全屏",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "还原",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 出错浮层。小窗里不弹：尺寸不够摆不下按钮，而且这时候用户也没法操作，
        // 摆上去只会把画面整个盖住。
        if (!inPip) holder.error?.let { err ->
            val urls = room.streamUrls(c.prefs.rtmpDomain, StreamMode.of(c.streamMode))
            ErrorOverlay(
                message = err,
                url = urls.firstOrNull() ?: "",
                onRetry = { holder.retry() },
                onExternal = { openExternal(context, urls) },
                onClose = onClose
            )
        }

        // 暂停指示
        if (!inPip && !holder.playing && holder.ready && holder.error == null) {
            PauseBadge(onClick = { holder.togglePlay() })
        }
    }
}

/**
 * 视频层：整页唯一实例。缩放/位移走 graphicsLayer，不碰布局 —— 播放器是个平台 View，
 * 每次改尺寸都会触发一次 surface 重建，滑动手感会直接崩掉。
 *
 * 转场用的竖直平移在外层「页面」Box 上做（见 PlayerScreen），这里是画面自身的
 * 缩放/平移（双指捏合），两层互不干扰。
 */
// pointerInteropFilter 至今仍标着 @ExperimentalComposeUiApi，必须显式 opt-in，
// 否则编译直接报 "This API is experimental"（error 级，不是警告）
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VideoLayer(
    holder: PlayerHolder,
    ui: PlayerUi,
    alpha: Float,
    resizeMode: Int,
    blockTouch: Boolean = false
) {
    AndroidView(
        factory = { ctx ->
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.view_player, null, false) as PlayerView
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            view.useController = false
            view.player = holder.player()
            view
        },
        update = { view ->
            if (view.player !== holder.player()) view.player = holder.player()
            view.resizeMode = resizeMode
        },
        // 离开播放页时把 player 从 View 上摘掉，否则 PlayerView 会一直攥着解码器
        onRelease = { view -> view.player = null },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = ui.zoom
                scaleY = ui.zoom
                translationX = ui.panX
                translationY = ui.panY
            }
            // 画中画里必须把触摸让出去。
            //
            // PlayerView 是平台 View，挂在 Compose 里时**它的触摸优先级高于同级的 Compose 节点**：
            // 铺满全屏的 PlayerView 会先把 DOWN 事件吃掉，导致叠在上面的 Compose 按钮
            // （底部「还原」）永远收不到点击 —— 表现就是「有一层透明遮罩，点不到放大按钮」。
            // PiP 下播放器本身不需要任何触摸（系统菜单接管），所以整块拦掉最干净。
            .then(
                if (blockTouch) {
                    Modifier.pointerInteropFilter { false } // 消费掉，不再往下传
                } else Modifier
            )
    )
}

/**
 * 手势层（对标 91zb 的 SwipeLayout）。
 *
 * 关键点：
 *  - 单指：先不动，等竖直位移过 touchSlop 且明显大于水平位移才接管 → 整页跟手平移。
 *    松手时由 [PlayerPager.finish] 决定切间（两段动画）还是回弹。
 *  - 如果这一串事件已经被某个子 View（聊天列表 / 按钮）消费掉，就整串放行，不抢。
 *    这样聊天区照常滚动、按钮照常点击，只有"没人要"的竖直拖拽才用来切间。
 *  - 双击 / 单击 / 双指捏合的逻辑沿用原实现。
 */
@Composable
private fun PlayerGestureLayer(
    pager: PlayerPager,
    ui: PlayerUi,
    holder: PlayerHolder,
    enabled: Boolean
) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker().apply {
                        resetTracking()
                        addPosition(down.uptimeMillis, down.position)
                    }
                    // 按下时若已被下层（按钮等）消费，说明这一串手势归它
                    var childHandled = down.isConsumed
                    var moved = false
                    var dragging = false
                    var pinched = false
                    var last = down.position
                    var lastDy = 0f
                    while (true) {
                        // 原始事件：别人消费过也照读，用来判断"这手势是不是别人在处理"
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // 双指：取消单指拖拽，转做缩放
                            if (dragging) {
                                dragging = false
                                pager.cancelDrag(scope)
                            }
                            pinched = true
                            val a = pressed[0]
                            val b = pressed[1]
                            val prevDist = (a.previousPosition - b.previousPosition).getDistance()
                            val currDist = (a.position - b.position).getDistance()
                            val factor =
                                if (prevDist > 1f && currDist > 1f) currDist / prevDist else 1f
                            val prevCenter = (a.previousPosition + b.previousPosition) / 2f
                            val currCenter = (a.position + b.position) / 2f
                            ui.applyPinch(
                                factor,
                                currCenter.x - prevCenter.x,
                                currCenter.y - prevCenter.y,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (event.changes.none { it.pressed }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val totalDx = change.position.x - down.position.x
                        val totalDy = change.position.y - down.position.y
                        if ((change.position - last).getDistance() > 14f) moved = true
                        last = change.position
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (!dragging && !pinched) {
                            if (change.isConsumed) childHandled = true
                            // 竖直意图明显：位移过 slop 且比水平大。缩放态不切间（留给平移）
                            if (!childHandled && ui.zoom <= 1.01f &&
                                abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx)
                            ) {
                                dragging = true
                            }
                        }
                        if (dragging) {
                            pager.drag(totalDy)
                            lastDy = totalDy
                            // 一旦接管就消费，避免子 View 同时再滚一下
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (dragging) {
                        pager.finish(scope, lastDy, tracker.calculateVelocity().y)
                        return@awaitEachGesture
                    }
                    if (pinched || moved || childHandled) return@awaitEachGesture
                    // 单击：面板藏起来了就立刻叫回来（不等双击判定，否则用户第二下会正好变双击）
                    if (!ui.chrome) {
                        ui.chrome = true
                        return@awaitEachGesture
                    }
                    if (ui.tapJob?.isActive == true) {
                        // 第二下 = 双击：缩放态先复位，否则就是暂停/继续
                        ui.tapJob?.cancel()
                        if (ui.zoom > 1.01f) ui.resetZoom() else holder.togglePlay()
                    } else {
                        // 挂 260ms 等第二下，没有第二下才算单击
                        ui.tapJob = scope.launch {
                            delay(260)
                            ui.chrome = !ui.chrome
                        }
                    }
                }
            }
    )
}

@Composable
private fun TopBar(
    room: Room,
    c: AppContainer,
    ui: PlayerUi,
    alpha: Float,
    pipAvailable: Boolean,
    onEnterPip: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    room.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = textShadow())
                )
                Text(
                    "${room.cat} · ${fmtCount(room.online)} 人在看 · 房间 ${room.id}",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    style = TextStyle(shadow = textShadow())
                )
            }
            IconButton(onClick = { c.toggleFollow(room) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "关注",
                    tint = if (room.id in c.followIdSet()) Gold else Color.White.copy(alpha = 0.6f)
                )
            }
            Pill(
                text = when {
                    ui.zoom > 1.01f -> "×%.1f".format(ui.zoom)
                    ui.scaleMode == 2 -> "拉伸"
                    else -> "适应"
                },
                alpha = 0.85f,
                onClick = {
                    if (ui.zoom > 1.01f) {
                        // 已经捏合放大过：点一下先还原，再点才切适应/拉伸
                        ui.resetZoom()
                    } else {
                        ui.scaleMode = if (ui.scaleMode == 2) 0 else 2
                        c.scaleMode = ui.scaleMode
                    }
                }
            )
            if (pipAvailable) {
                Spacer(Modifier.width(8.dp))
                Pill(text = "浮窗", alpha = 0.85f, onClick = onEnterPip)
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SideRail(room: Room, c: AppContainer, pageLabel: String, alpha: Float) {
    Column(
        Modifier.padding(end = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val followed = room.id in c.followIdSet()
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable { c.toggleFollow(room) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (followed) "取消关注" else "关注",
                    tint = if (followed) Gold else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    if (followed) "已关注" else "关注",
                    color = if (followed) Gold else Color.White.copy(alpha = 0.92f),
                    fontSize = 9.sp,
                    style = TextStyle(shadow = textShadow())
                )
            }
        }
        if (pageLabel.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.graphicsLayer { this.alpha = alpha },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    pageLabel,
                    color = Color.White,
                    fontSize = 10.sp,
                    style = TextStyle(shadow = textShadow()),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "↑↓",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    style = TextStyle(shadow = textShadow())
                )
            }
        }
    }
}

@Composable
private fun SwipeHint(onDismiss: () -> Unit) {
    Text(
        "↑ 上下滑动切换直播间 ↓",
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(top = 96.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun PauseBadge(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/** 顶栏小胶囊按钮；scaleMode 和浮窗都用它，省掉两份重复的 Modifier 链 */
@Composable
private fun Pill(text: String, alpha: Float, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White.copy(alpha = alpha),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun CoverPage(room: Room, coverAlpha: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = coverAlpha }
            .background(avBrush(room.id))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f)
                        )
                    )
                )
        )
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                initialOf(room.name),
                fontSize = 92.sp,
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(6.dp))
            Text(
                room.name,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${room.cat} · ${fmtCount(room.online)} 人在看",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ChatPanel(
    c: AppContainer,
    chat: ChatState,
    panelAlpha: Float,
    height: androidx.compose.ui.unit.Dp,
    hintVisible: Boolean,
    onDismissHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages = c.danmaku.messages
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pinned by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(messages.size, pinned) {
        if (pinned && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val bg = chat.bg
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = panelAlpha }
            .pointerInput(Unit) {
                // 右滑 → 背景变透明；左滑 → 恢复。跟手，不用等手势结束
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    chat.bg = (chat.bg - dragAmount / 400f).coerceIn(0f, 1f)
                }
            }
            .then(
                if (bg > 0.02f) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = bg * 0.10f),
                                Color.Black.copy(alpha = bg * 0.72f),
                                Color.Black.copy(alpha = bg * 0.86f)
                            )
                        )
                    )
                } else Modifier
            )
    ) {
        ChatHeader(
            c = c,
            chat = chat,
            messageCount = messages.size,
            pinned = pinned,
            onJumpBottom = {
                scope.launch {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                }
            }
        )

        if (hintVisible) {
            Text(
                "← 在这个区域向右滑动，弹幕会变透明，不挡画面",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCCFF5C7A))
                    .clickable { onDismissHint() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        // 没有消息时把"为什么没有"说清楚：
        // 以前这里会被内置样本填满，看起来一切正常，实际上一条实时弹幕都没收到。
        if (messages.isEmpty()) {
            val why = when {
                c.danmaku.status.isBlank() -> "正在连接弹幕…"
                // 状态文案现在带端口（`已进房 :37679`），判前缀不判全等
                c.danmaku.status.startsWith("已进房") -> "已进房，这个房间现在没人说话"
                else -> "弹幕未连接 · ${c.danmaku.status}"
            }
            Text(
                why,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
                style = TextStyle(shadow = textShadow())
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(messages) { m -> ChatLine(m) }
        }

        ChatInputBar(c = c, bg = bg)
    }
}

@Composable
private fun ChatHeader(
    c: AppContainer,
    chat: ChatState,
    messageCount: Int,
    pinned: Boolean,
    onJumpBottom: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "弹幕 $messageCount",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            style = TextStyle(shadow = textShadow())
        )
        // 弹幕链路状态直接写在标题旁：以前连不上会被样本顶上，用户完全看不出来
        val dmStatus = c.danmaku.status
        val dmHint = if (dmStatus.isBlank() || dmStatus == "已进房") "" else dmStatus
        if (dmHint.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                dmHint,
                color = Color(0xFFFFC46B).copy(alpha = 0.9f),
                fontSize = 10.sp,
                style = TextStyle(shadow = textShadow())
            )
        }
        Spacer(Modifier.weight(1f))
        if (!pinned) {
            Text(
                "↓ 回到底部",
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onJumpBottom)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        // 点一下在 62% / 25% / 全透明之间循环，比记手势友好
        Text(
            "背景 ${(chat.bg * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable {
                    chat.bg = when {
                        chat.bg > 0.5f -> 0.25f
                        chat.bg > 0.1f -> 0f
                        else -> 0.62f
                    }
                }
                .padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ChatInputBar(c: AppContainer, bg: Float) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun fire() {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        err = ""
        scope.launch {
            val r = c.danmaku.send(t)
            busy = false
            r.onSuccess {
                text = ""
                focus.clearFocus()
            }.onFailure { err = it.message ?: it.javaClass.simpleName }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f * (0.35f + bg)))
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        "说点什么…",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (err.isNotEmpty()) err = ""
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { fire() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp)
                )
            }
            // 发送键：忙的时候压暗并禁点，避免连点发出好几条一样的
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (busy) Color.White.copy(alpha = 0.08f)
                        else Gold.copy(alpha = 0.22f)
                    )
                    .clickable(enabled = !busy) { fire() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "发送弹幕",
                    tint = if (busy) Color.White.copy(alpha = 0.3f) else Gold,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        if (err.isNotEmpty()) {
            Text(
                "发送失败 · $err",
                color = Color(0xFFFF8F6B),
                fontSize = 10.sp,
                // 失败原因里会带 room/menu 和每个端口的结果，一两行足够看清是哪类问题
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                style = TextStyle(shadow = textShadow())
            )
        }
    }
}

@Composable
private fun ChatLine(m: DmMessage) {
    // 礼物单独一个暖金色：聊天区混着几十条白字，送礼全靠眼睛扫很容易漏掉
    val gift = m.kind == "gift"
    val accent = Color(0xFFFFC46B)
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = if (gift) accent else nickColor(m.nick),
                    fontWeight = FontWeight.SemiBold
                )
            ) { append(m.nick) }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.42f))) { append("：") }
            withStyle(
                SpanStyle(color = if (gift) accent else Color.White.copy(alpha = 0.96f))
            ) { append(m.text) }
        },
        fontSize = 13.sp,
        lineHeight = 18.sp,
        style = TextStyle(shadow = textShadow())
    )
}

private fun textShadow() = Shadow(
    color = Color.Black.copy(alpha = 0.85f),
    offset = Offset(0f, 1f),
    blurRadius = 5f
)

/** 用外部播放器打开（系统播放器 / VLC 等），兜底排查是流的问题还是解码器的问题 */
private fun openExternal(context: Context, urls: List<String>) {
    val u = urls.firstOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Compose 里的 LocalContext 常常是 ContextThemeWrapper，往上扒到 Activity 才能拿 window */
private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun ErrorOverlay(
    message: String,
    url: String,
    onRetry: () -> Unit,
    onExternal: () -> Unit,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                url.ifBlank { "还没填 RTMP 域名，去设置里补上" },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("重试", primary = true, onClick = onRetry)
                PillButton("外部播放器", primary = false, onClick = onExternal)
                PillButton("返回", primary = false, onClick = onClose)
            }
        }
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (primary) Color.White else Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
