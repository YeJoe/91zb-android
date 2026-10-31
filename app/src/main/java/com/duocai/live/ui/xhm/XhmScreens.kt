package com.tm.live.ui.xhm

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tm.live.AppContainer
import com.tm.live.data.xhm.XhmRoom
import com.tm.live.ui.avBrush
import com.tm.live.ui.fmtCount
import com.tm.live.ui.initialOf
import kotlinx.coroutines.launch

/**
 * 小红帽三屏：首页（分类 + 房间列表）/ 关注 / 播放页（聊天 + 发弹幕）。
 *
 * 与天美的 UI 风格刻意保持同一套（同一主题、同一卡片语言、同一渐变头像），
 * 但数据与交互完全独立 —— 换平台时用户应该感觉是「换了个 App 的数据」，
 * 而不是「换了个 App」。
 */

// ===================================================================== 首页

@Composable
fun XhmHomeScreen(c: AppContainer) {

    LaunchedEffect(Unit) { c.xhmBootstrap() }

    val rooms = c.xhmFilteredRooms()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶部：汉堡 + 标题 + 刷新
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { c.requestDrawer() }) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "平台切换",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "小红帽 LIVE",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    when {
                        c.xhmLoading -> "加载中…"
                        c.xhmError != null -> c.xhmError ?: ""
                        else -> "${rooms.size} 个直播间 · ${c.xhmCats.size} 个分类" +
                                if (c.xhmPrefs.demo || c.xhmPrefs.token.isBlank()) " · 演示数据" else ""
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { if (!c.xhmLoading) c.xhmReload() }) {
                if (c.xhmLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 分类 chips：全部 + 各分类
        LazyRow(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                XhmCatChip("全部", c.xhmCat.isBlank()) { c.xhmCat = "" }
            }
            items(c.xhmCats.size) { i ->
                val cat = c.xhmCats[i]
                XhmCatChip(cat, c.xhmCat == cat) { c.xhmCat = if (c.xhmCat == cat) "" else cat }
            }
        }

        if (rooms.isEmpty() && !c.xhmLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    c.xhmError ?: "暂无直播间",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = rememberLazyGridState(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rooms, key = { it.id }) { room ->
                XhmRoomCard(
                    room = room,
                    followed = room.id in c.xhmFollows.ids(),
                    version = c.xhmFollowVersion,
                    onOpen = { c.xhmOpenPlayer(rooms, rooms.indexOfFirst { it.id == room.id }) },
                    onToggleFollow = { c.xhmToggleFollow(room) }
                )
            }
        }
    }
}

@Composable
private fun XhmCatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface,
            labelColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun XhmRoomCard(
    room: XhmRoom,
    followed: Boolean,
    version: Int,
    onOpen: () -> Unit,
    onToggleFollow: () -> Unit
) {
    // version 只用来触发重组：本地关注变了，卡片上的星标要立刻变
    @Suppress("UNUSED_EXPRESSION") version

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(avBrush(room.id))
            ) {
                if (room.cover.isNotBlank()) {
                    AsyncImage(
                        model = room.cover,
                        contentDescription = null,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        initialOf(room.name),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 人气角标
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4D4F))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            fmtCount(room.online),
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        room.cat + if (room.fans > 0) " · ${fmtCount(room.fans)} 粉丝" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (followed) "取消关注" else "关注",
                    tint = if (followed) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onToggleFollow)
                )
            }
        }
    }
}

// ===================================================================== 关注

@Composable
fun XhmFollowScreen(c: AppContainer) {

    // version 当 key：本机关注增删后整页重算
    val follows = remember(c.xhmFollowVersion) { c.xhmFollows.all() }
    val byId = c.xhmRooms.associateBy { it.id }
    val items = follows.map { f ->
        // 列表里没有的（已下播/数据未含）也保留，播放时再尝试取流
        byId[f.id] ?: XhmRoom(id = f.id, name = f.name, cat = f.cat, online = 0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { c.requestDrawer() }) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "平台切换",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "小红帽 · 关注",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "${items.size} 个关注（本机收藏）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "还没有关注的小红帽主播",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "去首页点房间卡片右下角的 ★ 收藏",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { room ->
                XhmFollowRow(
                    room = room,
                    followed = true,
                    onOpen = { c.xhmOpenPlayer(items, items.indexOfFirst { it.id == room.id }) },
                    onToggleFollow = { c.xhmToggleFollow(room) }
                )
            }
        }
    }
}

@Composable
private fun XhmFollowRow(
    room: XhmRoom,
    followed: Boolean,
    onOpen: () -> Unit,
    onToggleFollow: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(avBrush(room.id)),
                contentAlignment = Alignment.Center
            ) {
                if (room.avatar.isNotBlank()) {
                    AsyncImage(
                        model = room.avatar,
                        contentDescription = null,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        initialOf(room.name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    room.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    room.cat,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (followed) "取消关注" else "关注",
                tint = if (followed) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onToggleFollow)
            )
        }
    }
}

// ===================================================================== 播放页

/**
 * 小红帽播放页：复用全局 ExoPlayer（RTMP 直拉），上层盖主播信息 + 聊天区。
 *
 * 与天美播放页的差异：小红帽是「标准直播 App」形态 —— 聊天区固定在底部半透明
 * 覆盖层，输入框常驻（发送弹幕），上下滑动切房间。
 */
@Composable
fun XhmPlayerScreen(c: AppContainer, startIndex: Int, onClose: () -> Unit) {

    val rooms = c.xhmPlaylist
    var index by remember { mutableStateOf(startIndex.coerceIn(0, rooms.lastIndex)) }
    val room = rooms.getOrNull(index) ?: rooms.first()
    var streamLoading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 换房间：取流 → 交给全局播放器（RTMP/HLS 都走它）；聊天重连
    LaunchedEffect(room.id) {
        c.xhmChat.clear()
        c.xhmChat.connect(room.id)
        streamLoading = true
        c.xhmRepo.getStreamUrl(room.id).onSuccess { url ->
            if (url.isNotBlank()) c.player.open(listOf(url), autoPlay = true)
        }.onFailure { e ->
            toast = "取流失败：${e.message}"
        }
        streamLoading = false
    }

    // 离开播放页：聊天断开（播放器由 RootScreen/容器管）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { c.xhmChat.close() }
    }

    // 关注操作回执 toast
    LaunchedEffect(c.xhmFollowSyncSeq) {
        val msg = c.xhmFollowSync ?: return@LaunchedEffect
        toast = msg
        c.consumeXhmFollowSync()
        kotlinx.coroutines.delay(2200)
        if (toast == msg) toast = null
    }

    // 上下滑动切房间（accumulate 到阈值就切，不等手势结束，跟抖音手感一致）
    var dragAcc by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(index) {
                detectVerticalDragGestures(
                    onDragStart = { dragAcc = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragAcc += amount
                        when {
                            dragAcc < -dp2px(120f, this) -> {
                                dragAcc = 0f
                                if (index < rooms.lastIndex) index++
                            }
                            dragAcc > dp2px(120f, this) -> {
                                dragAcc = 0f
                                if (index > 0) index--
                            }
                        }
                    }
                )
            }
    ) {
        // 播放器画面
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(com.tm.live.R.layout.view_player, null, false) as PlayerView
                view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                view.useController = false
                view.player = c.player.player()
                view
            },
            update = { view ->
                if (view.player !== c.player.player()) view.player = c.player.player()
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // 底部向上渐变，垫住聊天区文字
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )

        // 顶栏：返回 + 主播信息 + 关注
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(avBrush(room.id)),
                contentAlignment = Alignment.Center
            ) {
                if (room.avatar.isNotBlank()) {
                    AsyncImage(
                        model = room.avatar,
                        contentDescription = null,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        initialOf(room.name),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    room.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${fmtCount(room.online)} 人观看 · ${room.cat}" +
                            " · ${index + 1}/${rooms.size}",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val followed = room.id in c.xhmFollows.ids()
            Surface(
                shape = RoundedCornerShape(50),
                color = if (followed) Color.White.copy(alpha = 0.2f) else Color(0xFFFF4D6D),
                modifier = Modifier.clickable { c.xhmToggleFollow(room) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (followed) "已关注" else "关注",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 聊天区 + 输入框（底部常驻）
        ChatPanel(
            c = c,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        )

        // 取流中提示
        if (streamLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
            )
        }

        // toast
        toast?.let { msg ->
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp)
            ) {
                Text(
                    msg,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // 播放器报错提示
        c.player.error?.let { err ->
            Text(
                err,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

private fun dp2px(dp: Float, scope: PointerInputScope): Float =
    dp * scope.density.density

/** 直播间底部：聊天消息流（半透明） + 发送弹幕输入框 */
@Composable
private fun ChatPanel(c: AppContainer, modifier: Modifier = Modifier) {
    val msgs = c.xhmChat.msgs
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 新消息到达时滚到底
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }

    var input by remember { mutableStateOf("") }

    Column(modifier.imePadding().navigationBarsPadding()) {
        // 消息流：最多露出 6 条左右的高度
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                when (m.kind) {
                    "sys" -> Text(
                        "— ${m.text} —",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp
                    )
                    "enter" -> Text(
                        "${m.nick} 进入直播间",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                    "gift" -> Text(
                        "${m.nick} 送出 ${m.text}",
                        color = Color(0xFFFFD98F),
                        fontSize = 12.sp
                    )
                    else -> Text(
                        buildString {
                            if (m.nick.isNotBlank()) append("${m.nick}：")
                            append(m.text)
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发个弹幕…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    cursorColor = Color.White,
                    focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f)
                )
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = Color(0xFFFF4D6D),
                modifier = Modifier.clickable {
                    if (c.xhmChat.send(input)) input = ""
                }
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "发送",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(18.dp)
                )
            }
        }
    }
}
