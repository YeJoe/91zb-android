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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.duocai.live.AppContainer
import com.duocai.live.Feed
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
    /** 附近页选中的城市；空 = 用容器里记住的，再空 = 取房间最多的那个 */
    var city by remember { mutableStateOf(c.nearbyCity) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    // 刷新结果提示：容器把结果写进 toast，这里弹一次再清掉
    LaunchedEffect(c.toast) {
        val msg = c.toast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        c.toast = null
    }

    val all = c.rooms

    // 「附近」没有定位能力，降级成同城：取列表里出现最多的 city_show 当默认
    val topCity = remember(all) {
        val counts = LinkedHashMap<String, Int>()
        for (r in all) if (r.city.isNotBlank()) counts[r.city] = (counts[r.city] ?: 0) + 1
        counts.entries.maxByOrNull { it.value }?.key ?: ""
    }
    val activeCity = city.ifBlank { c.nearbyCity }.ifBlank { topCity }

    // 本地收藏 ∪ 服务端关注
    val followIds = remember(c.followedIds, c.serverFollowIds) { c.followedIds + c.serverFollowIds }

    // 一级 tab 先把池子筛出来，后面的分类/搜索只在池子里做
    val base = remember(all, c.feed, followIds, activeCity) {
        when (c.feed) {
            Feed.FOLLOW -> all.filter { it.id in followIds }
            Feed.HOT -> all
            Feed.RECOMMEND -> recommendOrder(all)
            Feed.NEARBY ->
                if (activeCity.isBlank()) emptyList() else all.filter { it.city == activeCity }
        }
    }

    val catChips = remember(base) {
        val counts = LinkedHashMap<String, Int>()
        for (r in base) counts[r.cat] = (counts[r.cat] ?: 0) + 1
        listOf("全部" to base.size) +
            counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    val cityChips = remember(all) {
        val counts = LinkedHashMap<String, Int>()
        for (r in all) if (r.city.isNotBlank()) counts[r.city] = (counts[r.city] ?: 0) + 1
        counts.entries.sortedByDescending { it.value }.take(16).map { it.key to it.value }
    }

    val list = remember(base, cat, query, sortOnline, c.showAd, c.feed) {
        var a = if (c.showAd) base else base.filter { !it.ad }
        if (c.feed != Feed.NEARBY && cat != "全部") a = a.filter { it.cat == cat }
        val q = query.trim()
        if (q.isNotEmpty()) {
            a = a.filter { it.name.contains(q, ignoreCase = true) || it.id.toString().contains(q) }
        }
        // 推荐页的顺序是算出来的，别被排序按钮盖回去
        if (c.feed == Feed.RECOMMEND) a
        else if (sortOnline) a.sortedByDescending { it.online } else a.sortedBy { it.name }
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
                .padding(start = 16.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "DC LIVE",
                    fontSize = 20.sp,
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

        // 一级 tab：关注 / 推荐 / 热门 / 附近
        FeedTabs(cur = c.feed, onSelect = {
            c.feed = it
            cat = "全部"
        })

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                placeholder = { Text("主播名 / 房间号") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
        }

        // 二级 chips：附近页是城市，其余是内容分类；关注页不需要
        when {
            c.feed == Feed.NEARBY -> ChipRow(
                chips = cityChips,
                selected = activeCity,
                onSelect = {
                    city = it
                    c.nearbyCity = it
                }
            )

            c.feed != Feed.FOLLOW -> ChipRow(
                chips = catChips,
                selected = cat,
                onSelect = { cat = it }
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (c.feed == Feed.RECOMMEND) {
                Text(
                    "人气分档 · 智能打散",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    if (sortOnline) "按人气" else "按名字",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { sortOnline = !sortOnline }
                )
            }
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
            val offAir = followIds.size - base.size
            when {
                list.isEmpty() && (c.loading || !c.loadedOnce) -> LoadingBox()
                list.isEmpty() && c.feed == Feed.FOLLOW && followIds.isEmpty() ->
                    HintBox(
                        title = "还没关注任何主播",
                        desc = "在卡片右上角点星标，或在直播间里点右侧的星；关注过的人会汇总到这里",
                        action = "去推荐看看",
                        onAction = { c.feed = Feed.RECOMMEND }
                    )

                list.isEmpty() && c.feed == Feed.FOLLOW -> HintBox(
                    title = "关注的主播都没开播",
                    desc = "共 ${followIds.size} 位关注，当前一个都不在播",
                    action = if (offAir > 0) "去关注页看全部" else null,
                    onAction = { c.tab = Tab.FOLLOW }
                )

                list.isEmpty() && c.feed == Feed.NEARBY -> HintBox(
                    title = "没取到同城房间",
                    desc = if (activeCity.isBlank()) "房间里没有城市信息，换「推荐」看看"
                    else "「$activeCity」眼下没有在播的房间，换个城市试试",
                    action = "换推荐",
                    onAction = { c.feed = Feed.RECOMMEND }
                )

                list.isEmpty() -> EmptyBox(c)

                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    items(list, key = { it.id }) { room ->
                        RoomCard(
                            c = c,
                            room = room,
                            followed = room.id in followIds,
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

/**
 * 一级 tab 行。做成下划线而不是 chip：
 * chip 是「筛选条件」的视觉语言，这排是「页面切换」，两者混在一屏里会看串。
 */
@Composable
private fun FeedTabs(cur: Feed, onSelect: (Feed) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top
    ) {
        Feed.entries.forEach { f ->
            val on = cur == f
            Column(
                Modifier.clickable { if (!on) onSelect(f) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    f.label,
                    fontSize = if (on) 17.sp else 15.sp,
                    fontWeight = if (on) FontWeight.Black else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .size(width = if (on) 20.dp else 0.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun ChipRow(
    chips: List<Pair<String, Int>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chips.size) { i ->
            val (name, count) = chips[i]
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(name) },
                label = { Text("$name $count", fontSize = 12.sp) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

private fun subtitleOf(c: AppContainer, shown: Int): String {
    val parts = ArrayList<String>(3)
    parts.add("$shown 个直播间")
    val at = c.meta.fetchedAt
    if (c.liveMode && at > 0L) {
        val ago = (System.currentTimeMillis() - at) / 1000
        parts.add(
            when {
                ago < 8 -> "刚刚更新"
                ago < 60 -> "${ago}秒前"
                ago < 3600 -> "${ago / 60}分钟前"
                else -> "${ago / 3600}小时前"
            }
        )
    } else if (c.meta.fromCache) {
        parts.add("本地缓存")
    }
    if (c.error != null && c.rooms.isNotEmpty()) parts.add("刷新失败")
    return parts.joinToString(" · ")
}

/**
 * 推荐排序：**不能纯随机**，也不能纯按人气（那就和「热门」一模一样）。
 *
 * 做法是人气分 6 档，档内按房间号哈希打散，再逐档轮流取 ——
 * 结果是头部仍然是人气高的，但同一档里不会固定成同一批人，
 * 而且同一份数据每次算出来的顺序一致（哈希只跟房间号有关），列表不会乱跳。
 */
private fun recommendOrder(rooms: List<Room>): List<Room> {
    if (rooms.size < 8) return rooms
    val per = (rooms.size / 6).coerceAtLeast(1)
    val tiers = rooms.sortedByDescending { it.online }
        .chunked(per)
        .map { tier -> tier.sortedBy { seed(it.id) } }
    val out = ArrayList<Room>(rooms.size)
    var i = 0
    while (out.size < rooms.size) {
        for (t in tiers) if (i < t.size) out.add(t[i])
        i++
        if (i > rooms.size) break
    }
    return out
}

private fun seed(id: Long): Long {
    var x = id xor (id ushr 33)
    x *= -0x61C88647L          // 2654435761 的补码写法，乘法故意溢出，只要散布均匀
    x = x xor (x ushr 29)
    x *= -0x7A14A32DL          // 2246822507
    x = x xor (x ushr 32)
    return x and 0x7FFFFFFFL
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
private fun HintBox(title: String, desc: String, action: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(action) }
            }
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
                c.error ?: "去设置里填好实时源的会话 Token，或换一份 JSON 数据源",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { c.tab = Tab.SETTINGS }) { Text("去设置") }
        }
    }
}

@Composable
private fun RoomCard(c: AppContainer, room: Room, followed: Boolean, onClick: () -> Unit) {
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
            // 兜底底色：图片没加载出来时先是一片渐变，不会闪白
            .background(avBrush(room.id))
            .clickable(onClick = onClick)
    ) {
        if (room.avatar.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(room.avatar)
                    .crossfade(180)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                initialOf(room.name),
                modifier = Modifier.align(Alignment.Center),
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.9f)
            )
        }

        // 底部压暗，保证文字在亮色封面/渐变上都读得清
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.82f)
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
                // 头像底上有深有浅，给星标垫一层半透明黑，否则浅色封面时看不见
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.26f))
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
