package com.duocai.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duocai.live.AppContainer
import com.duocai.live.Tab
import com.duocai.live.data.Room
import com.duocai.live.ui.theme.Gold
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(c: AppContainer) {

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var cat by remember { mutableStateOf("全部") }
    var sortOnline by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current

    val all = c.rooms
    val cats = remember(all) {
        val counts = LinkedHashMap<String, Int>()
        for (r in all) counts[r.cat] = (counts[r.cat] ?: 0) + 1
        val head = listOf("全部" to all.size)
        val rest = counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        head + rest
    }

    val list = remember(all, cat, query, sortOnline, c.showAd) {
        var a = if (c.showAd) all else all.filter { !it.ad }
        if (cat != "全部") a = a.filter { it.cat == cat }
        val q = query.trim()
        if (q.isNotEmpty()) {
            a = a.filter { it.name.contains(q, ignoreCase = true) || it.id.toString().contains(q) }
        }
        if (sortOnline) a.sortedByDescending { it.online } else a.sortedBy { it.name }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶部
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "DC LIVE",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    subtitleOf(c, list.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { if (!c.loading) scope.launch { c.reload() } }) {
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

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                placeholder = { Text("主播名 / 房间号") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
        }

        // 分类
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(cats.size) { i ->
                val (name, count) = cats[i]
                FilterChip(
                    selected = cat == name,
                    onClick = { cat = name },
                    label = { Text("$name $count", fontSize = 12.sp) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (sortOnline) "按人气" else "按名字",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { sortOnline = !sortOnline }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (c.showAd) "含推广位" else "只看真人",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { c.showAd = !c.showAd }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${list.size} 间",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                list.isEmpty() && (c.loading || !c.loadedOnce) -> LoadingBox()
                list.isEmpty() -> EmptyBox(c)
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    items(list, key = { it.id }) { room ->
                        RoomCard(
                            c = c,
                            room = room,
                            onClick = {
                                val index = list.indexOfFirst { it.id == room.id }
                                c.openPlayer(list, index)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun subtitleOf(c: AppContainer, shown: Int): String {
    val meta = c.meta
    val parts = ArrayList<String>(3)
    parts.add("$shown 个直播间")
    if (meta.buildAt.isNotBlank()) parts.add("快照 ${meta.buildAt.take(10)}")
    if (meta.fromCache) parts.add("本地缓存")
    if (c.error != null && c.rooms.isNotEmpty()) parts.add("刷新失败")
    return parts.joinToString(" · ")
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(10.dp))
            Text("正在取房间列表…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyBox(c: AppContainer) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (c.error != null) "数据源没加载成功" else "还没有房间数据",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                c.error ?: "去设置里填一个数据源地址，或直接粘贴一份 JSON",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { c.tab = Tab.SETTINGS }) { Text("去设置") }
        }
    }
}

@Composable
private fun RoomCard(c: AppContainer, room: Room, onClick: () -> Unit) {
    val followed = room.id in c.followedIds
    val ratio = when ((room.id % 5).toInt()) {
        0 -> 0.72f
        1 -> 0.90f
        2 -> 0.78f
        3 -> 0.84f
        else -> 0.76f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(avBrush(room.id))
            .clickable(onClick = onClick)
    ) {
        Text(
            initialOf(room.name),
            modifier = Modifier.align(Alignment.Center),
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.9f)
        )

        // 底部压暗，保证文字在亮色渐变上也读得清
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
        ) {
            Text(
                room.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(Color(0xFFFF5C7A), CircleShape)
                )
                Spacer(Modifier.width(4.dp))
                Text(fmtCount(room.online), color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(room.cat, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                if (room.city.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        room.city,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (room.ad) {
            Text(
                "推广",
                fontSize = 10.sp,
                color = Color(0xFF3A2A00),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Gold)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }

        IconButton(
            onClick = { c.toggleFollow(room) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(34.dp)
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (followed) "取消关注" else "关注",
                tint = if (followed) Gold else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
