package com.tm.live.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 头像是取不到的（接口域名公网不通，avatar 是相对路径），
 * 所以沿用网页版的做法：用房间号取一个固定渐变色 + 名字首字，视觉上反而更统一。
 */
private val PALETTE = listOf(
    Color(0xFFFF6B81) to Color(0xFFC44569),
    Color(0xFF5B9DFF) to Color(0xFF3B5BDB),
    Color(0xFF3DDC97) to Color(0xFF12805C),
    Color(0xFFF5B544) to Color(0xFFC47F17),
    Color(0xFFB48CFF) to Color(0xFF7048E8),
    Color(0xFFFF9F68) to Color(0xFFD95F02),
    Color(0xFF4DD0E1) to Color(0xFF0097A7),
    Color(0xFFFF87AB) to Color(0xFFC2185B),
    Color(0xFF9CCC65) to Color(0xFF558B2F),
    Color(0xFF7986CB) to Color(0xFF303F9F),
    Color(0xFFFFD54F) to Color(0xFFF57F17),
    Color(0xFF4DB6AC) to Color(0xFF00695C)
)

fun colorPairOf(id: Long): Pair<Color, Color> {
    val i = ((abs(id) % PALETTE.size) + PALETTE.size).toInt() % PALETTE.size
    return PALETTE[i]
}

fun avBrush(id: Long): Brush {
    val (a, b) = colorPairOf(id)
    return Brush.linearGradient(listOf(a, b))
}

/** 取名字里第一个汉字，没有就第一个字母/数字 */
fun initialOf(name: String): String {
    for (ch in name) {
        if (ch in '\u4e00'..'\u9fa5') return ch.toString()
    }
    for (ch in name) {
        if (ch.isLetterOrDigit() && ch.code < 128) return ch.uppercase()
    }
    return "·"
}

fun fmtCount(n: Int): String = when {
    n >= 10_000 -> {
        val v = n / 10_000.0
        val s = String.format("%.1f", v).removeSuffix(".0")
        "${s}万"
    }
    else -> n.toString()
}

/** 弹幕昵称配色：按名字哈希取一个柔和的亮色，别用纯白砸在一起 */
private val NICK_COLORS = listOf(
    Color(0xFF8FD9FF), Color(0xFFFFC1D4), Color(0xFFB9E6A6), Color(0xFFFFD98F),
    Color(0xFFD5B8FF), Color(0xFF9FF0E0), Color(0xFFFFB49B), Color(0xFFC5D2FF)
)

fun nickColor(nick: String): Color {
    if (nick.isEmpty()) return NICK_COLORS[0]
    var h = 0
    for (ch in nick) h = h * 31 + ch.code
    return NICK_COLORS[((h % NICK_COLORS.size) + NICK_COLORS.size) % NICK_COLORS.size]
}
