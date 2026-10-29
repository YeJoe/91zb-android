package com.duocai.live.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.WindowManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.duocai.live.AppContainer
import com.duocai.live.MainActivity
import com.duocai.live.R
import com.duocai.live.data.DmMessage
import com.duocai.live.data.Room
import com.duocai.live.data.StreamMode
import com.duocai.live.player.PlayerHolder
import com.duocai.live.ui.theme.Gold
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
 * 播放页。
 *
 * 三条硬规矩：
 *  1. 全页只有一个 ExoPlayer，切房只换 MediaItem（多实例必 OOM）
 *  2. 页面里只画封面，视频层固定在最底下 —— 滑动时是封面在动，不是视频在动
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
    val pager = rememberPagerState(initialPage = safeStart) { rooms.size }
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
    val inPip = host?.inPip == true
    val pipAvailable = remember(host) { host?.pipSupported() == true }

    // 画中画参数跟着「自动进小窗」开关走。API 31+ 由系统靠 setAutoEnterEnabled
    // 自己决定进入时机，不再在 onUserLeaveHint 里手动进（那条路会和系统手势动画抢
    // 时序，导致小窗上的展开/关闭按钮点不动 —— 见 MainActivity.syncPip）。
    LaunchedEffect(host, pipAvailable, c.autoPip) {
        if (host != null && pipAvailable) host.syncPip(c.autoPip)
    }

    val settled = pager.settledPage
    val offset = pager.currentPageOffsetFraction
    val room = rooms.getOrNull(settled) ?: rooms[safeStart]

    // 换房 → 换流；同一个地址不会重新握手
    LaunchedEffect(settled) {
        val r = rooms.getOrNull(settled) ?: return@LaunchedEffect
        ui.resetZoom()
        holder.open(
            r.streamUrls(c.prefs.rtmpDomain, StreamMode.of(c.streamMode)),
            c.autoPlay
        )
        // 弹幕跟着换房走：同一条隧道上重开 WS，打新房间的 /websock/join
        c.danmaku.start(this, r.id)
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
    val chromeAlpha = if (inPip) 0f else chromeSwitch * (1f - min(1f, abs(offset) * 2.2f))

    // 缩放模式只剩「适应 / 拉伸」两种预设；任意倍率靠双指捏合（1×–3×）
    val resizeMode =
        if (ui.scaleMode == 2) AspectRatioFrameLayout.RESIZE_MODE_FILL
        else AspectRatioFrameLayout.RESIZE_MODE_FIT

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = enterAlpha }
    ) {
        VideoLayer(holder, ui, videoAlpha, resizeMode)

        // 分页层：每页只有封面
        VerticalPager(
            state = pager,
            key = { index -> rooms[index].id },
            userScrollEnabled = !inPip && ui.zoom <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val coverAlpha = if (page == settled) 1f - videoAlpha else 1f
            CoverPage(room = rooms[page], coverAlpha = coverAlpha)
        }

        GestureLayer(ui, holder, enabled = !inPip)

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
                        pageLabel = if (rooms.size > 1) "${settled + 1}/${rooms.size}" else "",
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
                )
            }
        }

        // 小窗里系统自带的「展开 / 关闭」控件在部分 ROM 上会失灵（点了完全没反应），
        // 这里自己画一个还原按钮兜底：点一下直接把任务拉回前台回到全屏。
        // 放右上角 + 半透明，尽量不挡画面。
        if (inPip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable { host?.exitPipToFull() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ExitToApp,
                    contentDescription = "还原全屏",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(15.dp)
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
 * 视频层：整页唯一实例，固定不动。
 *
 * 缩放/位移全部走 graphicsLayer，不碰布局 —— 播放器是个平台 View，
 * 每次改尺寸都会触发一次 surface 重建，滑动手感会直接崩掉。
 */
@Composable
private fun VideoLayer(holder: PlayerHolder, ui: PlayerUi, alpha: Float, resizeMode: Int) {
    AndroidView(
        factory = { ctx ->
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.view_player, null, false) as PlayerView
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
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
    )
}

/**
 * 手势层。
 *
 * 刻意不用 detectTapGestures：它会 consume 掉 down，而这个层压在 Pager 上面，
 * 很容易把上下滑动吃掉。这里自己判"没移动就是点击"，单指全程不消费事件，
 * Pager 永远能滑；只有双指才消费，用来做缩放。
 *
 * 单指点击和双指捏合合并在同一个 awaitEachGesture 里（原来拆成两个 pointerInput，
 * 等于两份状态机加两份局部变量，正是撑爆方法体的元凶之一）。
 */
@Composable
private fun GestureLayer(ui: PlayerUi, holder: PlayerHolder, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var pinched = false
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
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
                        if ((change.position - last).getDistance() > 14f) moved = true
                        last = change.position
                    }
                    if (moved || pinched) return@awaitEachGesture
                    // 面板已经藏起来了：**任何一次单击都立刻把它叫回来**。
                    // 不能还走下面那套"等 260ms 看是不是双击"——用户看到面板消失后
                    // 往往马上再点一下，那一下会被判成双击（只切暂停），面板就再也回不来了。
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

        ChatInputBar(bg = bg)
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
        if (c.prefs.dmWs.isBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                "离线样本",
                color = Color.White.copy(alpha = 0.5f),
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
private fun ChatInputBar(bg: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f * (0.35f + bg)))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "说点什么…（只读，配置弹幕端点后可发）",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.Send,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp)
        )
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
