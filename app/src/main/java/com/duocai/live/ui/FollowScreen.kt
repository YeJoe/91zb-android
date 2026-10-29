package com.duocai.live.ui

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duocai.live.AppContainer
import com.duocai.live.data.Room
import com.duocai.live.ui.theme.Gold

/**
 * 关注页的关键不是"列人"，而是"谁现在在播"。
 * 本地收藏（房间里的人）+ 当前快照（在不在播）比对，直接分成两组，
 * 用户一眼就知道哪个能点进去。
 */
@Composable
fun FollowScreen(c: AppContainer) {

    // 本地收藏 ∪ 源 App 账号在服务端关注的人（podcast_id 就是房间号）
    val followIds = remember(c.followedIds, c.serverFollowIds) { c.followedIds + c.serverFollowIds }
    val localById = remember(c.followedIds) { c.follows.all().associateBy { it.id } }
    val byId = remember(c.rooms) { c.rooms.associateBy { it.id } }

    val live = ArrayList<Room>()
    val offline = ArrayList<Room>()
    for (id in followIds) {
        val hit = byId[id]
        if (hit != null && !hit.ad) {
            live.add(hit)
        } else {
            val local = localById[id]
            offline.add(
                hit ?: Room(
                    id,
                    local?.name ?: c.serverFollowNames[id] ?: "房间 $id",
                    local?.cat ?: "",
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (live.isNotEmpty()) {
                item { SectionTitle("正在直播 · ${live.size}", true) }
                items(live, key = { "live-${it.id}" }) { room ->
                    FollowRow(c, room, true, room.id in c.followedIds) { onPlay(c, live, room) }
                }
            }
            if (offline.isNotEmpty()) {
                item { SectionTitle("未开播 · ${offline.size}", false) }
                items(offline, key = { "off-${it.id}" }) { room ->
                    FollowRow(c, room, false, room.id in c.followedIds) { }
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = live, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (live) Modifier.background(avBrush(room.id))
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .alpha(if (live) 1f else 0.6f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initialOf(room.name),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (live) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (live) "${room.cat} · ${fmtCount(room.online)} 人在看" else "未开播 · 房间 ${room.id}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 服务端来的关注没有可用的取消接口，就不给按钮，避免点了没反应
        if (removable) {
            IconButton(onClick = { c.unfollow(room.id) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "取消关注",
                    tint = Gold,
                    modifier = Modifier.size(18.dp)
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
