package com.tm.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tm.live.AppContainer
import com.tm.live.Platform
import com.tm.live.Tab
import com.tm.live.ui.xhm.XhmFollowScreen
import com.tm.live.ui.xhm.XhmHomeScreen
import com.tm.live.ui.xhm.XhmPlayerScreen
import kotlinx.coroutines.launch

@Composable
fun RootScreen(c: AppContainer) {

    LaunchedEffect(Unit) { c.bootstrap() }

    // 手动刷新结果提示：以前分散在 HomeScreen / FollowScreen 里各放一个消费者。
    // 改成 tab 常驻组合后两个屏同时活着，同一条 toast 会被弹两遍 ——
    // 所以收到根上，全 App 只留这一个消费点。
    val ctx = LocalContext.current
    LaunchedEffect(c.toast) {
        val msg = c.toast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        c.toast = null
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 汉堡按钮（HomeScreen / XhmHomeScreen 顶栏）→ c.requestDrawer() → drawerTick++ → 这里开抽屉
    LaunchedEffect(c.drawerTick) {
        if (c.drawerTick > 0) drawerState.open()
    }

    // 系统返回键：播放页盖在所有 tab 之上。小红帽与天美各自一条播放序列，
    // 但同一时刻只可能有一条开着（openPlayer 互斥）。抽屉的返回键由
    // ModalNavigationDrawer 内部自行处理，这里不用管。
    BackHandler(enabled = c.platform == Platform.XHM && c.xhmOpenIndex >= 0) {
        c.xhmClosePlayer()
    }
    BackHandler(enabled = c.platform == Platform.TM && c.openIndex >= 0 && !c.inPip) {
        c.closePlayer()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PlatformDrawer(
                c = c,
                current = c.platform,
                onPick = { p ->
                    scope.launch { drawerState.close() }
                    val old = c.platform
                    c.platform = p
                    if (old != p) {
                        // 换平台 = 换数据源，正在播的另一边必须停（单 ExoPlayer）
                        if (p == Platform.XHM && c.openIndex >= 0) c.closePlayer()
                        if (p == Platform.TM && c.xhmOpenIndex >= 0) c.xhmClosePlayer()
                        if (p == Platform.XHM) c.xhmBootstrap()
                    }
                }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 五个列表页常驻组合（设置页两个平台共用一个实例），只切可见性。
                    // 以前用 Crossfade，每次切 tab 都把整页销毁重建 —— 首页要在动画
                    // 首帧同步重算四页池子（约 2000 房 × 过滤 + 排序）、重建 Pager 与
                    // 三个瀑布流、重挂全部卡片图片，这就是切 tab 卡顿的根源。
                    // 常驻之后切换零重建，滚动位置 / 搜索词 / 排序开关也全部保留。
                    val tmHome = c.platform == Platform.TM && c.tab == Tab.HOME
                    val tmFollow = c.platform == Platform.TM && c.tab == Tab.FOLLOW
                    val xhmHome = c.platform == Platform.XHM && c.tab == Tab.HOME
                    val xhmFollow = c.platform == Platform.XHM && c.tab == Tab.FOLLOW
                    val onSettings = c.tab == Tab.SETTINGS
                    KeepAliveTab(tmHome) { HomeScreen(c, visible = tmHome) }
                    KeepAliveTab(tmFollow) { FollowScreen(c) }
                    KeepAliveTab(xhmHome) { XhmHomeScreen(c) }
                    KeepAliveTab(xhmFollow) { XhmFollowScreen(c) }
                    KeepAliveTab(onSettings) { SettingsScreen(c) }
                }
                BottomBar(c)
            }

            // 播放页盖在最上层：不属于任何一个 tab，返回就落回原列表
            when (c.platform) {
                Platform.XHM -> {
                    val index = c.xhmOpenIndex
                    if (index >= 0 && c.xhmPlaylist.isNotEmpty()) {
                        XhmPlayerScreen(
                            c = c,
                            startIndex = index,
                            onClose = { c.xhmClosePlayer() }
                        )
                    }
                }
                Platform.TM -> {
                    val index = c.openIndex
                    if (index >= 0 && c.playlist.isNotEmpty()) {
                        PlayerScreen(
                            c = c,
                            startIndex = index,
                            onClose = { c.closePlayer() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 常驻 tab 页容器：首次可见时才组合（不给冷启动加负担），之后保持组合、只切可见性。
 *
 * 隐藏页做三件事：
 *  - alpha 0 —— 不显示；
 *  - zIndex 沉底 —— 绘制与命中测试顺序都让位给可见页；
 *  - 空 pointerInput —— 吃掉落进隐藏页的触摸。不加这条的话，点在可见页的
 *    空白区域（没有可命中节点的地方）会「穿透」到下面隐藏页里看不见却可点击
 *    的卡片上，出现幽灵点击。
 *
 * 常驻的代价是隐藏页仍会随数据刷新重组、偶尔空绘一轮，换来的是切 tab 零重建。
 */
@Composable
private fun KeepAliveTab(visible: Boolean, content: @Composable () -> Unit) {
    var visited by remember { mutableStateOf(false) }
    // 只会翻转一次（false → true），写成幂等，不会引发重组循环
    if (visible && !visited) visited = true
    if (!visited) return
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.pointerInput(Unit) {})
    ) { content() }
}

/**
 * 左侧抽屉：平台切换（看天美 / 看小红帽）。
 *
 * 抽屉只承担「换数据源」这一件事，条目做得大而醒目，选中态用品牌色块标出。
 * 小红帽未配置会话时给一条如实的状态提示（demo 数据），别让用户以为坏了。
 */
@Composable
private fun PlatformDrawer(c: AppContainer, current: Platform, onPick: (Platform) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                "TM LIVE",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "选择要看的平台",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))
            Platform.entries.forEach { p ->
                val selected = p == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onPick(p) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (p == Platform.XHM) Icons.Filled.LiveTv else Icons.Filled.Home,
                            contentDescription = p.label,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            p.subtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (c.xhmPrefs.demo || c.xhmPrefs.token.isBlank()) {
                Text(
                    "小红帽当前为演示数据（未配置会话）。设置页粘贴 jwt_token 后即为真实直播。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

private fun iconOf(tab: Tab): ImageVector = when (tab) {
    Tab.HOME -> Icons.Filled.Home
    Tab.FOLLOW -> Icons.Filled.Star
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun BottomBar(c: AppContainer) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = c.tab == tab,
                onClick = { c.tab = tab },
                icon = { Icon(iconOf(tab), contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
