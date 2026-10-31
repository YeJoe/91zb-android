package com.tm.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Crossfade(
                    targetState = c.tab to c.platform,
                    animationSpec = tween(180),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "tab"
                ) { (tab, platform) ->
                    when (platform) {
                        Platform.TM -> when (tab) {
                            Tab.HOME -> HomeScreen(c)
                            Tab.FOLLOW -> FollowScreen(c)
                            Tab.SETTINGS -> SettingsScreen(c)
                        }
                        Platform.XHM -> when (tab) {
                            Tab.HOME -> XhmHomeScreen(c)
                            Tab.FOLLOW -> XhmFollowScreen(c)
                            Tab.SETTINGS -> SettingsScreen(c)
                        }
                    }
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
