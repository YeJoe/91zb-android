package com.tm.live.ui

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
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.tm.live.AppContainer
import com.tm.live.Feed
import com.tm.live.Tab
import com.tm.live.data.Room
import com.tm.live.ui.theme.Gold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 首页。
 *
 * ## 顶部一级 tab 和内容区是同一个 Pager
 *
 * 「关注 / 推荐 / 热门 / 附近」四个页面用 `HorizontalPager` 摆成一条横向的轨道，
 * **滑动内容区就是切换 tab**，点 tab 也会带动画滚过去。两侧保持同步的方式是：
 *
 * - 手指滑 → `settledPage` 停了才写回 `c.feed`（滑动过程中不写，避免和手势抢状态，
 *   否则会出现"滑到一半被程序拽回去"的抖动）；
 * - 别处改 `c.feed`（比如空列表提示里的「去推荐看看」）→ 反过来把 pager 滚过去。
 *
 * 二级分类行（内容分类 / 城市）和排序行都放进了**每一页内部**，这样滑过去时
 * 分类条会跟着整页一起动，而不是在顶部硬切一下。
 *
 * 每个一级页各自记自己选中的分类（[cats]），从「热门·舞蹈」滑到「推荐」再滑回来，
 * 还是「热门·舞蹈」——不会被串成同一个值。
 */
@Composable
fun HomeScreen(c: AppContainer) {

    val scope = rememberCoroutineScope()
    /**
     * 输入框里正在打的字。
     *
     * 和 [query] 分开是刻意为之：以前两者是同一个变量，于是每敲一个字都要
     * 把上千个房间按名字/房间号过一遍 plus 重排四个 tab 的池子，中端机上
     * 打字明显掉帧。现在打字只更新这里，**点「搜索」或按回车**才写进 [query]。
     */
    var input by remember { mutableStateOf("") }
    /** 已提交的搜索词：只有它参与筛选 */
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var sortOnline by remember { mutableStateOf(true) }
    /** 附近页选中的城市；空 = 用容器里记住的，再空 = 取房间最多的那个 */
    var city by remember { mutableStateOf(c.nearbyCity) }
    /** 一级页 → 该页选中的二级分类 */
    val cats = remember { mutableStateMapOf<Feed, String>() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    // 刷新结果提示：容器把结果写进 toast，这里弹一次再清掉
    LaunchedEffect(c.toast) {
        val msg = c.toast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        c.toast = null
    }

    val all = c.rooms
    val feeds = Feed.entries

    // ---------------- Pager 与 c.feed 的双向同步 ----------------
    val pager = rememberPagerState(
        initialPage = feeds.indexOf(c.feed).coerceIn(0, feeds.lastIndex)
    ) { feeds.size }

    // 手指滑完 → 写回容器
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { i ->
            feeds.getOrNull(i)?.let { if (c.feed != it) c.feed = it }
        }
    }
    // 容器被别处改了 → 把 pager 滚过去
    LaunchedEffect(c.feed) {
        val i = feeds.indexOf(c.feed)
        if (i >= 0 && pager.currentPage != i && pager.targetPage != i) {
            pager.animateScrollToPage(i)
        }
    }

    // 当前页（拖动中会跟着走，tab 高亮因此是跟手的）
    val curFeed = feeds.getOrNull(pager.currentPage) ?: c.feed

    // 「附近」没有定位能力，降级成同城：取列表里出现最多的 city_show 当默认
    val topCity = remember(all) {
        val counts = LinkedHashMap<String, Int>()
        for (r in all) if (r.city.isNotBlank()) counts[r.city] = (counts[r.city] ?: 0) + 1
        counts.entries.maxByOrNull { it.value }?.key ?: ""
    }
    val activeCity = city.ifBlank { c.nearbyCity }.ifBlank { topCity }

    // 关注关系：服务端快照 ∪ 本地收藏。本地收藏增删时 followVersion 会变，必须一起做 key，
    // 否则在「关注」tab 里取消本地关注后列表不刷新。
    val followIds = remember(c.serverFollowIds, c.followVersion) { c.followIdSet() }

    // 关键：把 cats 摊平成普通 map 当 remember 的 key —— 直接传 mutableStateMap 的话
    // 改值不会让 remember 失效，分类筛选就不会重算。
    val catMap = cats.toMap()

    // 四页的列表一次性算好：既给每页用，也给顶部的「N 个直播间」用，
    // 免得滑动过程中反复对 2000 间房做过滤。
    val q = query.trim()
    // 搜索是**全局**的：不在当前 tab 的分类/城市里筛，否则站在「舞蹈」分类搜一个
    // 颜值主播会莫名搜不到，用户会以为搜索坏了。
    val hits = remember(all, q) { if (q.isEmpty()) emptyList() else searchRooms(all, q) }

    // 探活结果**刻意不放进 remember 的 key**：只有 orderEpoch 变了才重排。
    // 每探完一批就重排的话，用户滚到一半列表会突然换内容（探活是分批陆续回来的）。
    // 容器在「用户滑回顶部」时才 +1（见 AppContainer.markPendingOrder）。
    val epoch = c.orderEpoch
    val aliveNow = c.aliveIds
    val deadNow = c.deadIds

    val pool: Map<Feed, List<Room>> =
        remember(all, followIds, activeCity, catMap, q, sortOnline, hits, epoch) {
            if (q.isNotEmpty()) {
                feeds.associateWith { hits }
            } else {
                feeds.associateWith { f ->
                    // 一级先把池子筛出来
                    var a = when (f) {
                        Feed.FOLLOW -> all.filter { it.id in followIds }
                        Feed.HOT -> all
                        Feed.RECOMMEND -> recommendOrder(all)
                        Feed.NEARBY ->
                            if (activeCity.isBlank()) emptyList() else all.filter { it.city == activeCity }
                    }
                    // 游戏/棋牌/彩票导流房已经在 LiveRepo.toRoom 入口丢掉了（见 NonHuman），
                    // 这里不用再筛，`ad` 恒为 false，也没有"显示推广位"这种开关。
                    // 二级分类只有「热门」页有：那 8 个（游戏/星秀/女神/颜值/交友/音乐/舞蹈…）
                    // 是热门入口独有的口径，推荐页是算法排出来的混合流，按它筛没有意义。
                    val cat = catMap[f] ?: "全部"
                    if (f == Feed.HOT && cat != "全部") {
                        a = a.filter { it.cat == cat }
                    }
                    // 推荐页的顺序是算出来的，别被排序按钮盖回去
                    val base = if (f == Feed.RECOMMEND) a
                    else if (sortOnline) a.sortedByDescending { it.online } else a.sortedBy { it.name }

                    // 最后按探活结果分三档：可播 → 未探 → 探死不播。
                    // 用 sortedBy（稳定排序）保各档内部原有顺序，房间不会在档内乱跳。
                    // 只改顺序、不删房间 —— 删房会让列表在手指底下变短。
                    if (aliveNow.isEmpty() && deadNow.isEmpty()) base
                    else base.sortedBy { r ->
                        when {
                            r.id in aliveNow -> 0
                            r.id in deadNow -> 2
                            else -> 1
                        }
                    }
                }
            }
        }

    val cityChips = remember(all) {
        val counts = LinkedHashMap<String, Int>()
        for (r in all) if (r.city.isNotBlank()) counts[r.city] = (counts[r.city] ?: 0) + 1
        counts.entries.sortedByDescending { it.value }.take(16).map { it.key to it.value }
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
                    "TM LIVE",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    subtitleOf(c, pool[curFeed]?.size ?: 0),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    input = ""
                    query = ""
                }
            }) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { if (!c.refreshing) scope.launch { c.refreshManually() } }) {
                if (c.refreshing) {
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

        // 一级 tab：关注 / 推荐 / 热门 / 附近（点它 = 让 pager 滚过去，状态由 pager 回写）
        FeedTabs(cur = curFeed, onSelect = { f ->
            val i = feeds.indexOf(f)
            if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
        })

        if (searchOpen) {
            // 输入框 + 显式「搜索」按钮：搜索是手动触发的（打字时不筛）。
            // 原来靠 onValueChange 实时筛，两千个房间每个按键都要全表匹配 + 重排四个 tab，
            // 输入法联想还让按键变得更密，卡到没法用。
            val submit = {
                query = input.trim()
                keyboard?.hide()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("主播名 / 主播 ID（房间号）") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    // 清空输入框 = 放弃搜索，立刻回到列表：
                    // 留着旧的 query 会出现"框里没字但列表还是搜索结果的空状态"
                    trailingIcon = if (input.isNotEmpty()) {
                        {
                            IconButton(onClick = {
                                input = ""
                                query = ""
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                            }
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() })
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank() && input.trim() != query,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("搜索", fontSize = 13.sp)
                }
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // 一屏一页，不留边；横向翻页只吃「横向为主」的手势，
            // 纵向滚动仍然归每页里的 staggered grid
            beyondViewportPageCount = 1
        ) { page ->
            val f = feeds[page]
            FeedPage(
                c = c,
                feed = f,
                active = f == curFeed,
                rooms = pool[f].orEmpty(),
                cat = catMap[f] ?: "全部",
                onCat = { cats[f] = it },
                cityChips = cityChips,
                activeCity = activeCity,
                onCity = {
                    city = it
                    c.nearbyCity = it
                },
                followIds = followIds,
                sortOnline = sortOnline,
                onToggleSort = { sortOnline = !sortOnline },
                query = q,
                cols = c.gridCols,
                onToggleCols = { c.gridCols = if (c.gridCols >= 3) 2 else 3 },
                onOpen = { list, idx -> c.openPlayer(list, idx) }
            )
        }
    }
}

/**
 * 一个一级页：分类条 + 排序条 + 瀑布流。
 *
 * 单独抽出来是因为它要在 Pager 里被实例化 4 次（相邻页会预合成），
 * 状态全部由外部传入，页面本身不持有跨页的选择。
 *
 * 四个一级页（关注/推荐/热门/附近）各自一个 [PullToRefreshBox]，
 * 所以「每个分类都能下拉刷新」是结构化保证的，不是在某一个页面上挂了个手势：
 * 二级分类（热门下的游戏/舞蹈…）、附近的城市、以及搜索结果，
 * 用的都是同一个列表容器，换分类只是换了 [rooms]，下拉照旧能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPage(
    c: AppContainer,
    feed: Feed,
    /** 是不是「当前这一页」。Pager 会预合成相邻页，探活只该由看得见的那页发起 */
    active: Boolean,
    rooms: List<Room>,
    cat: String,
    onCat: (String) -> Unit,
    cityChips: List<Pair<String, Int>>,
    activeCity: String,
    onCity: (String) -> Unit,
    followIds: Set<Long>,
    sortOnline: Boolean,
    onToggleSort: () -> Unit,
    /** 当前搜索词（空 = 没在搜）。搜索结果是全局的，四页共用一份 */
    query: String,
    /** 瀑布流列数：3 = 紧凑（默认），2 = 大封面 */
    cols: Int,
    onToggleCols: () -> Unit,
    onOpen: (List<Room>, Int) -> Unit
) {
    val all = c.rooms
    val scope = rememberCoroutineScope()

    // 瀑布流状态提到外面：要读可见区间上报给探活（见下面的 LaunchedEffect）
    val gridState = rememberLazyStaggeredGridState()

    /**
     * 把「现在看得见第几到第几」报给容器，由它决定探哪些房间。
     *
     * 几个刻意的选择：
     *  - 只有 [active] 那页上报 —— 不然四页同时探，等于又变成全量；
     *  - 停 200ms 再发 —— 快滑时可见区间每帧都变，不去抖会一路滑一路探；
     *  - [AppContainer.orderEpoch] 变了要重启 —— 排完序可见区间还是那几行，但内容换了。
     *
     * 探活请求很轻（只读 3 个字节）且有 20 分钟 TTL（见 AppContainer.ensureProbed），
     * 滚动来回不会重复打。
     */
    LaunchedEffect(active, rooms, c.orderEpoch, c.fgEpoch, c.openIndex) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val v = gridState.layoutInfo.visibleItemsInfo
            if (v.isEmpty()) -1 to -1 else v.minOf { it.index } to v.maxOf { it.index }
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                if (first < 0) return@collectLatest
                delay(200)
                c.onFeedVisible(rooms, first, last)
            }
    }

    // 二级 chips：只有「热门」有内容分类（游戏/星秀/女神/颜值/交友/音乐/舞蹈… 共 8 个，
    // 是热门入口独有的口径）；「附近」用城市；「推荐」是算法混合流，「关注」是收藏，都不带分类条。
    val catChips = remember(feed, all) {
        val counts = LinkedHashMap<String, Int>()
        if (feed == Feed.HOT) {
            for (r in all) counts[r.cat] = (counts[r.cat] ?: 0) + 1
        }
        listOf("全部" to all.size) +
            counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索结果是全局的，分类/城市 chip 在这时候没有意义，收起来免得看着像"还能再筛"
        if (query.isEmpty()) {
            when (feed) {
                Feed.NEARBY -> ChipRow(chips = cityChips, selected = activeCity, onSelect = onCity)
                Feed.HOT -> ChipRow(chips = catChips, selected = cat, onSelect = onCat)
                else -> Unit
            }
        }

        SortBar(
            count = rooms.size,
            recommend = feed == Feed.RECOMMEND,
            sortOnline = sortOnline,
            onToggleSort = onToggleSort,
            query = query,
            cols = cols,
            onToggleCols = onToggleCols
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val offAir = followIds.size - rooms.size
            // 下拉刷新挂在**每一页**上（四页各一个），不是全局一个：
            // 手势要贴着这一页的列表，指示器也才能从这一页的顶部长出来。
            // 换二级分类 / 城市 / 搜索词只是换了 rooms，容器没换，所以下拉照旧可用。
            PullToRefreshBox(
                isRefreshing = c.refreshing,
                onRefresh = { scope.launch { c.refreshManually() } },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    // 空状态/提示页都套一层 RefreshFill：它们本身不会滚动，
                    // 不套的话手指拖下去收不到 nested scroll，看起来就是"下拉没反应"
                    rooms.isEmpty() && query.isNotEmpty() -> RefreshFill {
                        HintBox(
                            title = "没找到「${query.trim()}」",
                            desc = "名字里的一段字符、或者房间号（主播 ID）都能搜。\n只搜在播的真人直播间，游戏/棋牌房不参与搜索",
                            action = null,
                            onAction = {}
                        )
                    }

                    rooms.isEmpty() && (c.loading || !c.loadedOnce) -> RefreshFill { LoadingBox() }

                    rooms.isEmpty() && feed == Feed.FOLLOW && followIds.isEmpty() -> RefreshFill {
                        HintBox(
                            title = "还没关注任何主播",
                            desc = "在卡片右上角点星标，或在直播间里点右侧的星；关注过的人会汇总到这里",
                            action = "去推荐看看",
                            onAction = { c.feed = Feed.RECOMMEND }
                        )
                    }

                    rooms.isEmpty() && feed == Feed.FOLLOW -> RefreshFill {
                        HintBox(
                            title = "关注的主播都没开播",
                            desc = "共 ${followIds.size} 位关注，当前一个都不在播",
                            action = if (offAir > 0) "去关注页看全部" else null,
                            onAction = { c.tab = Tab.FOLLOW }
                        )
                    }

                    rooms.isEmpty() && feed == Feed.NEARBY -> RefreshFill {
                        HintBox(
                            title = "没取到同城房间",
                            desc = if (activeCity.isBlank()) "房间里没有城市信息，换「推荐」看看"
                            else "「$activeCity」眼下没有在播的房间，换个城市试试",
                            action = "换推荐",
                            onAction = { c.feed = Feed.RECOMMEND }
                        )
                    }

                    rooms.isEmpty() -> RefreshFill { EmptyBox(c) }

                    else -> {
                        // 紧凑档（3 列）连边距一起收：2 列时 14dp 的左右留白在 3 列下太奢侈，
                        // 每省 4dp 边距就是每格多 2-3dp 封面宽度。
                        val compact = cols >= 3
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(cols),
                            state = gridState,
                            contentPadding = PaddingValues(
                                start = if (compact) 9.dp else 12.dp,
                                end = if (compact) 9.dp else 12.dp,
                                top = 4.dp,
                                bottom = 18.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
                            verticalItemSpacing = if (compact) 7.dp else 9.dp
                        ) {
                            items(rooms, key = { it.id }) { room ->
                                RoomCard(
                                    c = c,
                                    room = room,
                                    compact = compact,
                                    followed = room.id in followIds,
                                    // 探活确认拉不到流：压暗沉底，但不从列表里拿掉
                                    // （删了会让列表在手指底下变短，也容易误杀）
                                    dim = room.id in c.deadIds,
                                    onClick = {
                                        val index = rooms.indexOfFirst { it.id == room.id }
                                        onOpen(rooms, index)
                                    }
                                )
                            }
                        }
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

/** 排序/过滤提示条：人气、过滤口径、当前页有几间 */
@Composable
private fun SortBar(
    count: Int,
    recommend: Boolean,
    sortOnline: Boolean,
    onToggleSort: () -> Unit,
    query: String,
    cols: Int,
    onToggleCols: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (query.isNotEmpty()) {
            // 搜索态：这一行只剩"在搜什么、还剩几间"，排序按钮藏起来
            // （结果按匹配度排，按人气/名字重排只会把命中项挤下去）
            Text(
                "搜索「$query」",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            if (recommend) {
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
                    modifier = Modifier.clickable(onClick = onToggleSort)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 固定文案：游戏/棋牌/彩票导流房已经在数据入口丢掉了，这里没有可切的开关
            Text(
                "只看真人",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
        }
        // 密度开关：点一下在「3 列（紧凑）/ 2 列（大封面）」之间切，选择会记住
        Text(
            if (cols >= 3) "3 列" else "2 列",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .clickable(onClick = onToggleCols)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count 间",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
 * 搜主播：**名字或 ID（房间号）都认**，结果按匹配强度排，同强度再按人气。
 *
 * 强度分级而不是简单 filter，是因为 ID 搜索会出现"包含"命中的一堆噪音
 * （搜 `1006` 能中几百个房间号），只有把「ID 完全相等」「ID 前缀相等」顶到前面，
 * 输入完整房间号时第一张卡才是要找的那间。
 */
private fun searchRooms(all: List<Room>, raw: String): List<Room> {
    val q = raw.trim()
    if (q.isEmpty()) return emptyList()
    val lower = q.lowercase()
    val asId = q.toLongOrNull()
    val scored = ArrayList<Pair<Room, Int>>(16)
    for (r in all) {
        val idText = r.id.toString()
        val score = when {
            asId != null && r.id == asId -> 0          // 房间号完全相等
            idText.startsWith(q) -> 1                  // 房间号前缀（输一半也能找到）
            r.name.lowercase().contains(lower) -> 2    // 名字包含
            idText.contains(q) -> 3                    // 房间号包含
            else -> -1
        }
        if (score >= 0) scored.add(r to score)
    }
    return scored
        .sortedWith(compareBy<Pair<Room, Int>> { it.second }.thenByDescending { it.first.online })
        .map { it.first }
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
private fun RoomCard(
    c: AppContainer,
    room: Room,
    /** 紧凑档（3 列）：整张卡片的所有尺寸一起缩，不是一个比例缩放 */
    compact: Boolean,
    followed: Boolean,
    /** 探活确认「此刻拉不到流」。压暗 + 标「未在播」，但**不移出列表** */
    dim: Boolean,
    onClick: () -> Unit
) {
    // 错落感保留：每格高度按房间号取固定值，滚动时不会因为重组而跳动。
    // 紧凑档把波动收窄（0.78~0.92），否则 3 列下长短差太大会留出成片的空洞。
    val ratio = if (compact) {
        when ((room.id % 4).toInt()) {
            0 -> 0.78f
            1 -> 0.88f
            2 -> 0.82f
            else -> 0.92f
        }
    } else {
        when ((room.id % 5).toInt()) {
            0 -> 0.72f
            1 -> 0.90f
            2 -> 0.78f
            3 -> 0.84f
            else -> 0.76f
        }
    }
    val corner = if (compact) 11.dp else 15.dp
    val padH = if (compact) 7.dp else 10.dp

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(corner))
            // 兜底底色：图片没加载出来时先是一片渐变，不会闪白
            .background(avBrush(room.id))
            // 压暗但不隐藏：alpha 只影响绘制，点击区域、列表位置都不变
            .alpha(if (dim) 0.38f else 1f)
            .clickable(onClick = onClick)
    ) {
        // 首字永远垫在最底下：图床（CloudFront）在部分网络下整段不可达，
        // AsyncImage 加载失败时是**透明的**，不垫底就只剩一片渐变，看着像没内容。
        Text(
            initialOf(room.name),
            modifier = Modifier.align(Alignment.Center),
            fontSize = if (compact) 26.sp else 42.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.9f)
        )
        if (room.avatar.isNotEmpty()) {
            // 列表每轮自动刷新都会重组。请求对象不 remember 的话每轮新建一个，
            // 就算最终命中内存缓存，也会重走一遍"加载 → 淡入"，看着就是图片在反复加载。
            // 网格封面属于背景元素，关掉淡入 —— 换图频率极低，淡入只会制造闪动。
            val ctx = LocalContext.current
            val req = remember(room.avatar) {
                ImageRequest.Builder(ctx).data(room.avatar).crossfade(false).build()
            }
            AsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
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
                .padding(start = padH, end = padH, bottom = if (compact) 6.dp else 9.dp)
        ) {
            Text(
                room.name,
                color = Color.White,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(if (compact) 2.dp else 3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(if (compact) 4.dp else 5.dp)
                        // 探活没通的房间，红点转灰：一眼能分辨「没人看」和「没在播」
                        .background(if (dim) Color(0xFF9E9E9E) else Color(0xFFFF5C7A), CircleShape)
                )
                Spacer(Modifier.width(if (compact) 3.dp else 4.dp))
                Text(
                    fmtCount(room.online),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = if (compact) 9.5.sp else 11.sp,
                    maxLines = 1
                )
                // 3 列时横向只剩 ~100dp，人气 + 分类已经把一行占满，
                // 城市再塞进来就会两个都被省略号吃掉，索性只在宽卡片里显示
                if (dim) {
                    Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        "未在播",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = if (compact) 9.5.sp else 11.sp,
                        maxLines = 1
                    )
                } else if (room.cat.isNotBlank()) {
                    Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        room.cat,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = if (compact) 9.5.sp else 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!compact && room.city.isNotEmpty()) {
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

        // 左上角钉主播 ID（就是房间号，也是关注接口里的 podcast_id）。
        // 搜索支持按 ID 搜，这里得能看到 ID 才能对照；顺带占住了原来「推广」角标的位置
        // （`ad` 现在恒为 false，那个角标不会再有）。
        Text(
            "ID ${room.id}",
            fontSize = if (compact) 8.sp else 9.5.sp,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 5.dp else 7.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.34f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        IconButton(
            onClick = { c.toggleFollow(room) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(if (compact) 0.dp else 2.dp)
                .size(if (compact) 26.dp else 32.dp)
                // 头像底上有深有浅，给星标垫一层半透明黑，否则浅色封面时看不见
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.26f))
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (followed) "取消关注" else "关注",
                tint = if (followed) Gold else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(if (compact) 13.dp else 17.dp)
            )
        }
    }
}
