package com.tm.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tm.live.AppContainer
import com.tm.live.data.Room
import com.tm.live.ui.theme.Gold
import kotlinx.coroutines.launch

/**
 * 关注页的关键不是"列人"，而是"谁现在在播"。
 * 本地收藏（房间里的人）+ 当前快照（在不在播）比对，直接分成两组，
 * 用户一眼就知道哪个能点进去。
 */
@Composable
fun FollowScreen(c: AppContainer) {

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = LocalContext.current

    // 关注关系以服务端为唯一真相源（本地不再持久化）
    val followIds = remember(c.serverFollowIds) { c.followIdSet() }
    val byId = remember(c.rooms) { c.rooms.associateBy { it.id } }

    LaunchedEffect(c.toast) {
        val msg = c.toast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        c.toast = null
    }

    val live = ArrayList<Room>()
    val offline = ArrayList<Room>()
    for (id in followIds) {
        val hit = byId[id]
        if (hit != null && !hit.ad) {
            live.add(hit)
        } else {
            // 列表里没有这个房间（人在别处开播/还没被拉到详情）：
            // 用服务端返回的昵称兜底，名字不会有歧义。
            offline.add(
                hit ?: Room(
                    id,
                    c.serverFollowNames[id] ?: "房间 $id",
                    "",
                    0,
                    "",
                    false,
                    0
                )
            )
        }
    }
    live.sortByDescending { it.online }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "关注",
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${followIds.size} 位",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { if (!c.loading) scope.launch { c.refreshManually() } }) {
                if (c.loading) {
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

        // 在播状态靠刷新才对得上，这里把「为什么可能是旧数据」说清楚
        if (!c.tunnelOk && c.rooms.isNotEmpty()) {
            Text(
                "源 App 没在后台运行，下面是上次拿到的在播状态（点右上角重试）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
            )
        }

        if (followIds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "还没有关注的主播",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在首页点封面右上角的星标就行。\n关注关系存在本机，不依赖账号。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (live.isNotEmpty()) {
                item { SectionTitle("正在直播 · ${live.size}", true) }
                items(live, key = { "live-${it.id}" }) { room ->
                    FollowRow(c, room, true, room.id in followIds) { onPlay(c, live, room) }
                }
            }
            if (offline.isNotEmpty()) {
                item { SectionTitle("未开播 · ${offline.size}", false) }
                items(offline, key = { "off-${it.id}" }) { room ->
                    FollowRow(c, room, false, room.id in followIds) { }
                }
            }
        }
    }
}

private fun onPlay(c: AppContainer, list: List<Room>, room: Room) {
    c.openPlayer(list, list.indexOfFirst { it.id == room.id }.coerceAtLeast(0))
}

@Composable
private fun SectionTitle(text: String, live: Boolean) {
    Row(
        Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(Color(0xFFFF3B5C), CircleShape)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FollowRow(
    c: AppContainer,
    room: Room,
    live: Boolean,
    removable: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = live, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (live) Modifier.background(avBrush(room.id))
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .alpha(if (live) 1f else 0.6f),
            contentAlignment = Alignment.Center
        ) {
            // 首字**永远画在最底下**：图床不可达（CloudFront 在部分网络下整段被封）时
            // AsyncImage 加载失败会画成透明，只留渐变，卡片就"空"了；这样至少有首字。
            Text(
                initialOf(room.name),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (live) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (room.avatar.isNotEmpty()) {
                val ctx = LocalContext.current
                val req = remember(room.avatar) {
                    ImageRequest.Builder(ctx).data(room.avatar).crossfade(150).build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (live) "${room.cat} · ${fmtCount(room.online)} 人在看 · ID ${room.id}"
                else "未开播 · ID ${room.id}",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 服务端来的关注没有可用的取消接口，就不给按钮，避免点了没反应
        if (removable) {
            IconButton(
                onClick = { c.unfollow(room.id) },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "取消关注",
                    tint = Gold,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Text(
                "源App",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}
