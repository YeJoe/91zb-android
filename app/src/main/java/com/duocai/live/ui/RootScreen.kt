package com.duocai.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.duocai.live.AppContainer
import com.duocai.live.Tab

@Composable
fun RootScreen(c: AppContainer) {

    LaunchedEffect(Unit) { c.bootstrap() }

    // 系统返回键：播放页盖在所有 tab 之上，但它只是画在 tab 上面的一层，
    // 没有任何地方拦截返回键 —— 于是按返回会直接 finish 掉 Activity，表现就是
    // "进了直播间按返回直接回桌面"。这里统一拦一下：播放页开着时返回 = 关掉播放页、
    // 落回原来的列表；小窗（PIP）状态下不拦，那会儿 Activity 不在前台，
    // 返回键归系统管（用来关小窗）。
    BackHandler(enabled = c.openIndex >= 0 && !c.inPip) {
        c.closePlayer()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            Crossfade(
                targetState = c.tab,
                animationSpec = tween(180),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "tab"
            ) { tab ->
                when (tab) {
                    Tab.HOME -> HomeScreen(c)
                    Tab.FOLLOW -> FollowScreen(c)
                    Tab.SETTINGS -> SettingsScreen(c)
                }
            }
            BottomBar(c)
        }

        // 播放页盖在最上层：不属于任何一个 tab，返回就落回原 tab
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
