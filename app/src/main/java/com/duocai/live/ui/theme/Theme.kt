package com.tm.live.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 品牌色：和网页版的强调色保持一致 */
val Brand = Color(0xFFFF5C7A)
val Gold = Color(0xFFFFC93C)

private val DarkScheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1622),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFF8E9AB8),
    onSecondary = Color(0xFF10131A),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE9EBF2),
    surface = Color(0xFF12151D),
    onSurface = Color(0xFFE9EBF2),
    surfaceVariant = Color(0xFF1A1F2A),
    onSurfaceVariant = Color(0xFFA5AEC2),
    outline = Color(0xFF2C3242),
    outlineVariant = Color(0xFF20252F)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFD3315A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0E6),
    onPrimaryContainer = Color(0xFF5C0E22),
    secondary = Color(0xFF5C6683),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF5A6274),
    outline = Color(0xFFC9CEDB),
    outlineVariant = Color(0xFFE2E5ED)
)

/**
 * @param mode 0 跟随系统 / 1 深色 / 2 浅色
 *
 * Android 12+ 用壁纸取色（Monet）铺表面色，但把 primary 换回品牌粉 ——
 * 全盘交给动态取色的结果是"什么颜色的 App 都有可能"，对直播类界面不划算。
 */
@Composable
fun TmTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark ->
            dynamicDarkColorScheme(context).copy(primary = Brand, onPrimary = Color.White)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context).copy(primary = Color(0xFFD3315A), onPrimary = Color.White)

        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
