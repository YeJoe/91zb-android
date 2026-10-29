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
 * 播放页。
 *
 * 三条硬规矩：
 *  1. 全页只有一个 ExoPlayer，切房只换 MediaItem（多实例必 OOM）
 *  2. 页面里只画封面，视频层固定在最底下 —— 滑动时是封面在动，不是视频在动，
 *     所以不存在"视频被划出去露出黑边"这种穿帮
 *  3. 弹幕区右滑变透明：不透明度从 0.62 一路滑到 0，文字加投影保证可读，不影响看画面
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
    val chat = remember { ChatState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // 单击/双击的仲裁：单击要等 260ms 看有没有第二下
    var tapJob by remember { mutableStateOf<Job?>(null) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var chrome by remember { mutableStateOf(true) }
    var entered by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(!c.prefs.sawChatSwipeHint) }
    // 竖滑切换的引导，只在第一次进播放页出现
    var swipeHint by remember { mutableStateOf(!c.prefs.sawPlayerSwipeHint) }
    // 缩放模式做成局部状态：点一下要立刻看到画面变化，不能等别的原因触发重组
    var scaleMode by remember { mutableStateOf(c.scaleMode) }

    LaunchedEffect(Unit) {
        entered = true
        c.danmaku.start(this)
        if (hintVisible) {
            kotlinx.coroutines.delay(4200)
            hintVisible = false
            c.prefs.sawChatSwipeHint = true
        }
    }

    LaunchedEffect(Unit) {
        if (swipeHint) {
            kotlinx.coroutines.delay(3200)
            swipeHint = false
            c.prefs.sawPlayerSwipeHint = true
        }
    }

    val settled = pager.settledPage
    val offset = pager.currentPageOffsetFraction
    val room = rooms.getOrNull(settled) ?: rooms[safeStart]

    // 换房 → 换流；同一个地址不会重新握手
    LaunchedEffect(settled) {
        val r = rooms.getOrNull(settled) ?: return@LaunchedEffect
        zoom = 1f
        panX = 0f
        panY = 0f
        holder.open(
            r.streamUrls(c.prefs.rtmpDomain, StreamMode.of(c.streamMode)),
            c.autoPlay
        )
    }

    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            playerViewRef.value?.player = null
            playerViewRef.value = null
            holder.pause()
            c.danmaku.stop()
        }
    }

    // 看直播也别让屏幕自己灭 —— 只在这一页生效，退出立刻还回去
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 画中画：进小窗后把顶栏、弹幕、手势层全收起来，只留画面
    val host = activity as? MainActivity
    val inPip = host?.inPip == true
    val pipAvailable = remember(host) { host?.pipSupported() == true }

    val enterAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(220), label = "enter")
    val videoAlpha by animateFloatAsState(
        if (holder.ready && holder.error == null) 1f else 0f,
        tween(260),
        label = "video"
    )
    val chromeSwitch by animateFloatAsState(if (chrome) 1f else 0f, tween(200), label = "chrome")
    val chromeAlpha =
        if (inPip) 0f else chromeSwitch * (1f - min(1f, abs(offset) * 2.2f))

    // 缩放模式只剩「适应 / 拉伸」两种预设；任意倍率靠双指捏合（1×–3×）
    val resizeMode =
        if (scaleMode == 2) AspectRatioFrameLayout.RESIZE_MODE_FILL
        else AspectRatioFrameLayout.RESIZE_MODE_FIT

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = enterAlpha }
    ) {
        // ---- 视频层：整页唯一实例，固定不动 ----
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_player, null, false) as PlayerView
                view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                view.player = holder.player()
                playerViewRef.value = view
                view
            },
            update = { view ->
                if (view.player !== holder.player()) view.player = holder.player()
                view.resizeMode = resizeMode
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = videoAlpha
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
        )

        // ---- 分页层：每页只有封面 ----
        VerticalPager(
            state = pager,
            key = { index -> rooms[index].id },
            userScrollEnabled = !inPip && zoom <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val r = rooms[page]
            val coverAlpha = if (page == settled) 1f - videoAlpha else 1f
            CoverPage(room = r, coverAlpha = coverAlpha)
        }

        // ---- 手势层 ----
        // 刻意不用 detectTapGestures：它会 consume 掉 down，而这个层压在 Pager 上面，
        // 很容易把上下滑动吃掉。这里自己判"没移动就是点击"，全程不消费事件，
        // Pager 永远能滑；只有双指才消费，用来做缩放。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(inPip) {
                    if (inPip) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if ((change.position - last).getDistance() > 14f) moved = true
                            last = change.position
                        }
                        if (!moved) {
                            if (tapJob?.isActive == true) {
                                // 第二下 = 双击：缩放态先复位，否则就是暂停/继续
                                tapJob?.cancel()
                                if (zoom > 1.01f) {
                                    zoom = 1f
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    holder.togglePlay()
                                }
                            } else {
                                // 挂 260ms 等第二下，没有第二下才算单击
                                tapJob = scope.launch {
                                    delay(260)
                                    chrome = !chrome
                                }
                            }
                        }
                    }
                }
                .pointerInput(inPip) {
                    if (inPip) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val a = pressed[0]
                                val b = pressed[1]
                                val prevDist =
                                    (a.previousPosition - b.previousPosition).getDistance()
                                val currDist = (a.position - b.position).getDistance()
                                val factor =
                                    if (prevDist > 1f && currDist > 1f) currDist / prevDist else 1f
                                val next = (zoom * factor).coerceIn(1f, 3f)
                                val prevCenter = (a.previousPosition + b.previousPosition) / 2f
                                val currCenter = (a.position + b.position) / 2f
                                zoom = next
                                if (next <= 1.02f) {
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    val maxX = size.width * (next - 1f) / 2f
                                    val maxY = size.height * (next - 1f) / 2f
                                    panX = (panX + (currCenter.x - prevCenter.x))
                                        .coerceIn(-maxX, maxX)
                                    panY = (panY + (currCenter.y - prevCenter.y))
                                        .coerceIn(-maxY, maxY)
                                }
                                event.changes.forEach { it.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
        )

        // ---- 顶栏（画中画时整体不渲染，免得小窗里误触返回）----
        if (!inPip) Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = chromeAlpha }
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
                val followed = room.id in c.followIdSet()
                IconButton(onClick = { c.toggleFollow(room) }) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "关注",
                        tint = if (followed) Gold else Color.White.copy(alpha = 0.6f)
                    )
                }
                Text(
                    when {
                        zoom > 1.01f -> "×%.1f".format(zoom)
                        scaleMode == 2 -> "拉伸"
                        else -> "适应"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable {
                            if (zoom > 1.01f) {
                                // 已经捏合放大过：点一下先还原，再点才切适应/拉伸
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            } else {
                                scaleMode = if (scaleMode == 2) 0 else 2
                                c.scaleMode = scaleMode
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                if (pipAvailable) {
                    Text(
                        "浮窗",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { host?.enterPip() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        // ---- 右侧：关注 / 第几间 / 竖滑提示 ----
        // 关注按钮不跟 chrome 一起淡出：刷到没关注的人，随手就能点，不用先把顶栏点出来。
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
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

            if (rooms.size > 1) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.graphicsLayer { alpha = chromeAlpha },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${settled + 1}/${rooms.size}",
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

        // 首次进播放页的竖滑引导（画中画不显示）
        if (swipeHint && !inPip) {
            Text(
                "↑ 上下滑动切换直播间 ↓",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .clickable {
                        swipeHint = false
                        c.prefs.sawPlayerSwipeHint = true
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // ---- 聊天区：右滑透明（画中画时隐藏）----
        if (!inPip) ChatPanel(
            c = c,
            chat = chat,
            panelAlpha = chromeAlpha,
            height = screenHeight * 0.40f,
            hintVisible = hintVisible,
            onDismissHint = {
                hintVisible = false
                c.prefs.sawChatSwipeHint = true
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // ---- 出错浮层 ----
        holder.error?.let { err ->
            val fallbacks = room.streamUrls(c.prefs.rtmpDomain, StreamMode.of(c.streamMode))
            ErrorOverlay(
                message = err,
                url = fallbacks.firstOrNull() ?: "",
                onRetry = { holder.retry() },
                onExternal = {
                    val u = fallbacks.firstOrNull()
                    if (u != null) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                },
                onClose = onClose
            )
        }

        // ---- 暂停指示 ----
        if (!inPip && !holder.playing && holder.ready && holder.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { holder.togglePlay() },
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
    }
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
        // 头
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "弹幕 ${messages.size}",
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
                        .clickable {
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            // 点一下在 62% / 25% / 全透明之间循环，比记手势友好
            Text(
                "背景 ${(bg * 100).toInt()}%",
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

        // 消息
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

        // 输入条（只读）
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
}

@Composable
private fun ChatLine(m: DmMessage) {
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(color = nickColor(m.nick), fontWeight = FontWeight.SemiBold)
            ) { append(m.nick) }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.42f))) { append("：") }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.96f))) { append(m.text) }
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
