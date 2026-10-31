package com.tm.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tm.live.AppContainer
import com.tm.live.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 解析导出工具给的配置；任一字段有值即视为有效。 */
private data class RealtimeConfig(
    val appToken: String?,
    val jwe: String?,
    val tunnelPort: Int?,
    val userId: Long? = null,
    val channelId: String? = null,
    // 小红帽段（可选）：{"xhm":{"token":..,"userId":..,"baseUrl":..,"imgBase":..,"demo":..}}
    val xhmToken: String? = null,
    val xhmUserId: String? = null,
    val xhmBaseUrl: String? = null,
    val xhmImgBase: String? = null,
    val xhmDemo: Boolean? = null
)

/**
 * 解析导出工具的配置：先试 JSON（{"appToken":..,"jwe":..,"tunnelPort":..,"userId":..,
 * "channelId":..,"xhm":{..}}），再退回纯文本标记（含【会话 Token】等）。
 * 返回 null = 啥也没解析出来。
 */
private fun parseRealtimeConfig(text: String): RealtimeConfig? {
    val s = text.trim()
    runCatching { JSONObject(s) }.getOrNull()?.let { j ->
        val t = j.optString("appToken").takeIf { it.isNotBlank() }
        val w = j.optString("jwe").takeIf { it.isNotBlank() }
        val p = j.optInt("tunnelPort", -1).let { if (it > 0) it else null }
        val u = j.optLong("userId", -1L).let { if (it > 0) it else null }
        val ch = j.optString("channelId").takeIf { it.isNotBlank() }
        val x = j.optJSONObject("xhm")
        val xt = x?.optString("token")?.takeIf { it.isNotBlank() }
        val xu = x?.optString("userId")?.takeIf { it.isNotBlank() }
        val xb = x?.optString("baseUrl")?.takeIf { it.isNotBlank() }
        val xi = x?.optString("imgBase")?.takeIf { it.isNotBlank() }
        val xd = if (x != null && x.has("demo")) x.optBoolean("demo") else null
        if (t != null || w != null || u != null || ch != null ||
            xt != null || xu != null || xb != null || xi != null || xd != null
        ) {
            return RealtimeConfig(t, w, p, u, ch, xt, xu, xb, xi, xd)
        }
    }
    val t = Regex("""【会话 Token】[^\n]*\n(\S+)""").find(s)?.groupValues?.get(1)
        ?.takeIf { !it.startsWith("(") }
    val w = Regex("""【JWE】[^\n]*\n(\S+)""").find(s)?.groupValues?.get(1)
        ?.takeIf { !it.startsWith("(") }
    val p = Regex("""【隧道端口】[^\n]*\n(\d{2,5})""").find(s)?.groupValues?.get(1)?.toIntOrNull()
    if (t == null && w == null) return null
    return RealtimeConfig(t, w, p)
}


@Composable
fun SettingsScreen(c: AppContainer) {

    val scope = rememberCoroutineScope()
    val prefs = c.prefs

    var url by remember { mutableStateOf(prefs.roomsUrl) }
    var domain by remember { mutableStateOf(prefs.rtmpDomain) }
    var img by remember { mutableStateOf(prefs.imgBase) }
    var imgProxy by remember { mutableStateOf(prefs.imgProxy) }
    var gateway by remember { mutableStateOf(prefs.hlsGateway) }
    var pasteOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 自更新：只认本应用的包（包名 + 签名），找到就覆盖安装
    val ctx = LocalContext.current
    var apkScanning by remember { mutableStateOf(false) }
    var apkNote by remember { mutableStateOf<String?>(null) }
    /** 「备用数据源」默认收起：实时源正常时这组根本不用碰 */
    var advancedOpen by remember { mutableStateOf(false) }

    // 弹幕端口诊断（点「诊断端口」后填充）
    var wsDiagLoading by remember { mutableStateOf(false) }
    var wsDiag by remember { mutableStateOf<String?>(null) }

    // 直播源（直连源 App 隧道端口）
    // JWE 已内置默认值（Prefs.Defaults.JWE），输入框就留空 —— 直接把 330 字符塞进
    // 单行输入框，用户只会看到一串乱码似的尾巴。要看/改时才自己粘一份进来。
    var jwe by remember {
        mutableStateOf(prefs.jwe.takeIf { it != Prefs.Defaults.JWE } ?: "")
    }
    var port by remember { mutableStateOf(if (prefs.tunnelPort > 0) prefs.tunnelPort.toString() else "") }
    // 会话 token（32 位 hex）：弹幕 join 的唯一身份凭证。导出工具直接给这一串，
    // 没必要让用户去「导入配置」里拼 JSON，所以单独一个框。
    var dmToken by remember { mutableStateOf(prefs.appToken.trim()) }

    // 内置隧道
    var appKey by remember { mutableStateOf(prefs.kiwiAppKey) }

    // 小红帽（XHM）平台会话
    var xhmToken by remember { mutableStateOf(prefs.xhmToken) }
    var xhmUserId by remember { mutableStateOf(prefs.xhmUserId) }
    var xhmBase by remember { mutableStateOf(prefs.xhmBaseUrl) }
    var xhmImg by remember { mutableStateOf(prefs.xhmImgBase) }
    var xhmDemo by remember { mutableStateOf(prefs.xhmDemo) }

    // 分页：-1 = 分类列表，0..5 = 各分类详情。设置项太多，一屏全铺开找不到东西，
    // 所以主页只留六行「类别 + 当前状态摘要」，点进去才展开。
    var page by rememberSaveable { mutableStateOf(-1) }
    // 物理返回键：详情页回列表，列表页交给系统（返回上一级界面）
    BackHandler(enabled = page >= 0) { page = -1 }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    // 浮层提示 2.6 秒自己走，别一直压在底部挡内容
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    // 主页摘要全是实时值：隧道/房间数/弹幕链路变了一眼就能看见，不用挨个点进去
    val liveSummary = when (c.tunnel.state) {
        com.tm.live.tunnel.Tunnel.State.READY -> "隧道在线"
        com.tm.live.tunnel.Tunnel.State.STARTING -> "隧道启动中"
        com.tm.live.tunnel.Tunnel.State.FAILED -> "隧道失败"
        else -> "隧道未启动"
    } + " · " + c.rooms.size + " 个直播间 · 端口 " +
        (if (c.tunnel.port > 0) c.tunnel.port.toString() else "自动")
    val playSummary = listOf("自动", "HTTP-FLV", "RTMP")[c.streamMode.coerceIn(0, 2)] +
        " · 缓冲 " + c.bufferMs / 1000.0 + "s"
    val dmSummary = c.danmaku.status.ifBlank { "未开始" } + " · 已收 " + c.danmaku.liveCount + " 条"
    val followSummary = "服务端 " + c.serverFollowIds.size
    val themeSummary = listOf("跟随系统", "深色", "浅色")[c.themeMode.coerceIn(0, 2)]
    val xhmSummary = when {
        prefs.xhmDemo -> "演示数据（未配置会话）"
        prefs.xhmToken.isBlank() -> "演示数据（token 为空）"
        else -> "已配置会话 · " + (if (c.xhmRooms.isEmpty()) "列表未拉取" else "${c.xhmRooms.size} 个房间")
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scroll)
                .padding(bottom = 24.dp)
        ) {
            if (page < 0) {
                Text(
                    "设置",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                )

                // 一屏六项，点进去才展开细节 —— 状态摘要直接写在这儿，不用进去也知道有没有问题
                Spacer(Modifier.height(2.dp))
                SettingEntry("直播源与隧道", liveSummary) { page = 0 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("播放", playSummary) { page = 1 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("弹幕", dmSummary) { page = 2 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("关注", followSummary) { page = 3 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("外观", themeSummary) { page = 4 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("小红帽", xhmSummary) { page = 6 }
                Spacer(Modifier.height(8.dp))
                SettingEntry("关于", "TM Live " + c.versionName) { page = 5 }
            } else {
                // 详情页：返回 + 本页标题，再走原来的 Section 卡片
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { page = -1 }
                        .padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("‹", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "设置",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val pageTitle = listOf("直播源与隧道", "播放", "弹幕", "关注", "外观", "关于", "小红帽")
                    .getOrElse(page) { "设置" }
                Text(
                    pageTitle,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)
                )

                if (page == 0) {
                    // ---------- 内置隧道 ----------
                    Section("内置隧道（不再需要源 App）") {
                        val t = c.tunnel
                        val dot = when (t.state) {
                            com.tm.live.tunnel.Tunnel.State.READY -> Color(0xFF35D07F)
                            com.tm.live.tunnel.Tunnel.State.STARTING -> Color(0xFFFFC24B)
                            com.tm.live.tunnel.Tunnel.State.FAILED -> Color(0xFFFF5C7A)
                            else -> Color(0xFF9AA0A6)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(dot)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                t.message,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        if (t.initCode != Int.MIN_VALUE) {
                            InfoLine("SDK Init 返回码 ${t.initCode}" + if (t.initCode == 0) "（0 = 握手成功）" else "")
                        }
                        // 每个 vhost 组的探测明细：`组=端口:状态码`，200 后面打 ✓ 就是命中的那个组。
                        // -5 = 组名不存在（SDK 组表里没有），404 = 组存在但远端没这个路径，
                        // -1 = 连不上/超时，-2 = 回了 200 但没有 rooms 字段。
                        if (t.diag.isNotBlank()) {
                            InfoLine("组探测：${t.diag}")
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val ok = c.tunnel.start(appKey)
                                        if (ok) {
                                            c.reload(force = true)
                                            toast = "内置隧道就绪 127.0.0.1:${c.tunnel.port}"
                                        } else {
                                            toast = c.tunnel.message
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = t.state != com.tm.live.tunnel.Tunnel.State.STARTING
                            ) {
                                Text(
                                    when (t.state) {
                                        com.tm.live.tunnel.Tunnel.State.STARTING -> "握手…"
                                        com.tm.live.tunnel.Tunnel.State.READY -> "已就绪"
                                        else -> "启动内置隧道"
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        toast = if (c.tunnel.restart(appKey)) {
                                            c.reload(force = true)
                                            "已重启，端口 ${c.tunnel.port}"
                                        } else c.tunnel.message
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = t.state != com.tm.live.tunnel.Tunnel.State.STARTING
                            ) { Text("重来") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        toast = "试 key 中…（每个都要握手，约 5 秒）"
                                        val r = c.tunnel.probeAll()
                                        toast = r
                                        if (c.tunnel.ready) c.reload(force = true)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = t.state != com.tm.live.tunnel.Tunnel.State.STARTING
                            ) { Text("试候选 key") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = appKey,
                            onValueChange = { appKey = it; prefs.kiwiAppKey = it },
                            label = { Text("云层 SDK appKey") },
                            placeholder = { Text("32 位，SDK 的 Init() 只认这一个参数") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        InfoLine(
                            "这个 key 是从源 App 运行内存里 dump 出来的真值（1432 字符 Base64，AES 密文，不是口令）。" +
                                "留空则完全不启用内置隧道。"
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启动时自动拉起", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = c.kiwiAuto,
                                onCheckedChange = { c.kiwiAuto = it }
                            )
                        }
                        InfoLine(
                            "通了之后源 App 就可以卸载：libkiwi.so 和 com.kiwi.sdk.* 都已经打进本 APK。" +
                                "没通会自动退回下面的「借源 App 端口」。"
                        )
                    }

                    // ---------- 直播源 ----------
                    Section("直播源（实时房间数据）") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (c.tunnelOk) Color(0xFF35D07F) else Color(0xFFFF5C7A))
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (c.tunnelOk) "隧道在线 · 127.0.0.1:${c.tunnelPort}"
                                else "未连接 · 源 App 没在后台跑",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "使用源 App 后台隧道（兜底）",
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = c.sourceTunnel,
                                onCheckedChange = { c.sourceTunnel = it }
                            )
                        }
                        InfoLine(
                            "默认关。内置隧道通了就完全不需要它；打开后才会去扫本机监听端口、借源 App 起好的隧道用" +
                                "（源 App 一退出就失效，所以只当应急）。"
                        )
                        Spacer(Modifier.height(4.dp))
                        InfoLine(
                            "房间 ${c.rooms.size} 个" +
                                if (c.meta.fromCache) " · 本地缓存" else "" +
                                if (c.error != null) " · 上次错误：${c.error}" else ""
                        )
                        // 探活结果：判断「列表里全是死房」到底是房间不对还是域名/网络不对。
                        // 只探可见窗口（结果缓存 20 分钟），所以这里的分母是「本轮探了几间」，不是全部房间。
                        if (c.probeInfo.isNotBlank()) {
                            InfoLine("在播探活（本屏）：${c.probeInfo}")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    c.reload(force = true)
                                    toast = when {
                                        c.tunnelOk -> "隧道在线，已更新 ${c.rooms.size} 个直播间"
                                        else -> c.error ?: "连不上隧道端口"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (c.loading) "连接中…" else "测试连接并刷新") }
                        Spacer(Modifier.height(6.dp))

                        OutlinedTextField(
                            value = jwe,
                            onValueChange = { jwe = it; prefs.jwe = it },
                            label = { Text("JWE（关注 / 弹幕用，已内置默认）") },
                            placeholder = { Text("留空即用内置凭证；换号时粘一份新的（330 字符）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        InfoLine(
                            if (jwe.isBlank()) {
                                "当前用**内置默认凭证**（随 App 打包），不用填。清空这一栏也等于用内置的。"
                            } else {
                                "当前用你自己粘贴的凭证（${jwe.trim().length} 字符）。清空则回到内置默认。"
                            }
                        )
                        InfoLine(
                            "拉直播间列表**完全不需要凭证**；`/user_follow/my_follow`（关注）和弹幕 join 要它。" +
                                "填一次长期有效，源 App 重启也不用重填（账号别登出就行）。"
                        )
                        // 换号要照着搬：把当前生效的 JWE 露出来（内置的也露），能展开、能复制
                        CredentialRow("当前生效 JWE", prefs.jwe.trim()) { toast = it }
                        InfoLine("换号 = JWE 和会话 token 一起换（两样都长在同一个账号上）；只换一个，会出现「关注还是旧号、弹幕进不去」")
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = {
                                port = it
                                prefs.tunnelPort = it.toIntOrNull() ?: 0
                            },
                            label = { Text("隧道端口（可选，留空自动探测）") },
                            placeholder = { Text("0 = 自动扫描 /proc/net/tcp") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        InfoLine("端口每次源 App 启动都会变（实测 38103 → 12987 → 41305），所以留空让它自己找最省事")
                        Spacer(Modifier.height(6.dp))
                        // 默认关：一轮就是整池重拉，定时刷属于"按需才会想开"的功能
                        val presetSecs = listOf(0, 60, 300, 1800)
                        val isCustom = c.refreshSec !in presetSecs
                        var customMin by remember {
                            mutableStateOf(
                                if (isCustom && c.refreshSec > 0) (c.refreshSec / 60).toString() else ""
                            )
                        }
                        ChipLine(
                            label = "自动刷新",
                            options = listOf("关", "1分钟", "5分钟", "30分钟", "自定义"),
                            selected = if (isCustom) 4 else presetSecs.indexOf(c.refreshSec).coerceAtLeast(0),
                            onSelect = { i ->
                                when (i) {
                                    0 -> c.refreshSec = 0
                                    1 -> c.refreshSec = 60
                                    2 -> c.refreshSec = 300
                                    3 -> c.refreshSec = 1800
                                    else -> c.refreshSec = (customMin.toIntOrNull() ?: 5) * 60
                                }
                            }
                        )
                        if (isCustom) {
                            OutlinedTextField(
                                value = customMin,
                                onValueChange = { raw ->
                                    customMin = raw.filter { it.isDigit() }.take(4)
                                    val m = customMin.toIntOrNull() ?: return@OutlinedTextField
                                    if (m > 0) c.refreshSec = m * 60
                                },
                                label = { Text("自定义间隔（分钟）") },
                                placeholder = { Text("比如 30") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        InfoLine(
                            "默认关闭，只在你点刷新/下拉时才拉。开着的话每 ${if (c.refreshSec > 0) "${c.refreshSec / 60} 分钟" else "（未定）"}重拉一遍在播状态与人数。"
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { importOpen = true }) { Text("导入配置（从导出工具粘贴）") }
                        InfoLine("把电脑上 token_export.py 的整段输出粘进来，JWE 一键填入")
                    }

                    // ---------- 备用数据源 ----------
                    Section("备用数据源（正常情况下用不到）") {
                        Text(
                            if (advancedOpen) "收起 ▲" else "展开 ▼",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { advancedOpen = !advancedOpen }
                        )
                        InfoLine(
                            "现在是实时模式：直连源 App 隧道，拿服务端当前在播列表。" +
                                "隧道连不上时先用本地缓存；缓存也没有，才轮到这里的远程 JSON。"
                        )
                        if (advancedOpen) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it; prefs.roomsUrl = it },
                                label = { Text("房间数据 URL") },
                                placeholder = { Text("https://…/rooms.json") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 3,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            c.reload(force = true)
                                            toast = c.error ?: "已更新 ${c.rooms.size} 个房间"
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("立即拉取") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { pasteOpen = true }) { Text("粘贴 JSON") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    url = Prefs.Defaults.ROOMS_URL
                                    prefs.roomsUrl = url
                                    toast = "已换成 jsDelivr 镜像"
                                }) { Text("jsDelivr 镜像", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    url = Prefs.Defaults.ROOMS_URL_RAW
                                    prefs.roomsUrl = url
                                    toast = "已换成 GitHub raw（国内可能连不上）"
                                }) { Text("GitHub raw", fontSize = 12.sp) }
                            }
                            InfoLine("仓库里那份 rooms.json 是 10-29 的快照，主播在播状态早就变了，只当应急用")
                        }
                        if (c.meta.buildAt.isNotBlank()) InfoLine("快照时间 ${c.meta.buildAt}")
                    }
                }

                // ---------- 播放 ----------

                if (page == 1) {
                    Section {
                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it; prefs.rtmpDomain = it },
                            label = { Text("流媒体域名") },
                            placeholder = { Text("xxx.hebeisaixin.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        InfoLine("播放地址 = http://<域名>/live/<房间号>.flv，不需要 token")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = img,
                            onValueChange = { img = it; prefs.imgBase = it },
                            label = { Text("封面图床") },
                            placeholder = { Text("https://…/images/480") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        InfoLine(
                            "封面 = 图床 + 接口里的 attachment_url（去掉 /static/upload 前缀）。" +
                                "改完回首页下拉一次才生效（房间对象里存的是拼好的完整 URL）"
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = imgProxy,
                            onValueChange = { imgProxy = it; c.imgProxy = it },
                            label = { Text("图片代理（可选，host:port）") },
                            placeholder = { Text("例如 127.0.0.1:7890") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        InfoLine(
                            "图床是 CloudFront（drdue73ptt7o.cloudfront.net），国内网络多数整段不通 —— " +
                                "实测设备上 TCP 连得上、请求却零响应，同一个 URL 在挂了代理的电脑上 200。" +
                                "有代理就填这里，图片会走它；留空直连。"
                        )
                        Spacer(Modifier.height(8.dp))
                        ChipLine(
                            label = "播放通道",
                            options = listOf("自动", "HTTP-FLV", "RTMP"),
                            selected = c.streamMode.coerceIn(0, 2),
                            onSelect = { c.streamMode = it }
                        )
                        InfoLine(
                            when (c.streamMode) {
                                1 -> "只走 HTTP-FLV：实测最稳，Media3 原生解码"
                                2 -> "只走 RTMP：librtmp 对该 CDN 兼容性差，一般不用"
                                else -> "自动：先 HTTP-FLV，失败再换 RTMP"
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = gateway,
                            onValueChange = { gateway = it; prefs.hlsGateway = it },
                            label = { Text("备用 HLS 网关（可选）") },
                            placeholder = { Text("http://192.168.1.10:8899") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        InfoLine("指到你 PC 上跑的 tools/gateway.py，RTMP 失败时自动降级")
                        Spacer(Modifier.height(10.dp))
                        val scaleValues = listOf(0, 2)
                        ChipLine(
                            label = "画面缩放",
                            options = listOf("适应", "拉伸"),
                            selected = scaleValues.indexOf(c.scaleMode).let { if (it < 0) 0 else it },
                            onSelect = { c.scaleMode = scaleValues[it] }
                        )
                        InfoLine("固定「裁剪」已去掉：播放时双指捏合可 1×–3× 自由放大/拖动，点顶栏胶囊一键还原")
                        Spacer(Modifier.height(8.dp))
                        val buffers = listOf(800, 1500, 3000)
                        ChipLine(
                            label = "缓冲",
                            options = buffers.map { "${it / 1000.0}s" },
                            selected = buffers.indexOf(c.bufferMs).let { if (it < 0) 1 else it },
                            onSelect = { c.bufferMs = buffers[it] }
                        )
                        InfoLine("缓冲档位下次启动生效（解码器只在创建时读一次）")
                        Spacer(Modifier.height(4.dp))
                        SwitchLine(
                            label = "进房自动播放",
                            desc = "关掉就先进暂停态，省流量",
                            checked = c.autoPlay,
                            onChange = { c.autoPlay = it }
                        )
                        Spacer(Modifier.height(4.dp))
                        InfoLine("画中画只在播放页点顶栏「浮窗」时进入，退到桌面不会自动缩窗")
                    }
                }

                // ---------- 弹幕 ----------

                if (page == 2) {
                    Section {
                        val dmState = c.danmaku.status.ifBlank { "未开始" }
                        InfoLine("走隧道里的 `/websock/…`：join 进房后只收实时 room_msg，不补历史 —— 没人说话就空着")
                        InfoLine("链路：$dmState · 本次已收 ${c.danmaku.liveCount} 条")
                        // 弹幕只能走业务 nginx 那个 vhost；列表用的端口能握手但 /websock/… 会 404
                        InfoLine("端口：im=${c.tunnel.imPort} · 列表=${c.tunnel.port}（为 0 表示隧道没起）")
                        // 会话 token 是 join 的唯一身份凭证：缺了 im 一定回 `403 bad token, please login!`，
                        // 端口再对也没用。之前只能靠「导入配置」粘整段 JSON，现在给一个直接填的框。
                        val tok = c.prefs.appToken.trim()
                        InfoLine(
                            "凭证：会话 token=" + (if (tok.isEmpty()) "空 → join 必然 403" else "${tok.take(6)}…${tok.takeLast(4)}") +
                                " · JWE=" + (if (c.prefs.jwe.trim().isEmpty()) "空" else "有")
                        )
                        OutlinedTextField(
                            value = dmToken,
                            onValueChange = { dmToken = it.trim() },
                            label = { Text("会话 token（32 位 hex）") },
                            placeholder = { Text("e24bae48…（源 App 一键导出【会话 Token】那行）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = dmToken.isNotEmpty() && !Regex("^[0-9a-fA-F]{32}$").matches(dmToken),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "保存并重连",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable {
                                        val v = dmToken.trim()
                                        if (!Regex("^[0-9a-fA-F]{32}$").matches(v)) {
                                            toast = "会话 token 要 32 位 hex（当前 ${v.length} 位）"
                                        } else {
                                            c.prefs.appToken = v
                                            toast = "已保存会话 token，正在重连弹幕"
                                            c.danmaku.restart(scope)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "每次源 App 冷启动都会变，变了就重导一次",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (tok.isNotEmpty()) {
                            CredentialRow("当前生效会话 token", tok) { toast = it }
                        }
                        val tried = c.danmaku.ports
                        InfoLine(
                            if (tried.isEmpty()) {
                                "本轮端口：还没开始试"
                            } else {
                                "本轮端口（按序试 join）：${tried.joinToString(" → ")}" +
                                    (if (tried.size <= 2) " · 快路径，失败后才扫全部 LISTEN" else "")
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        SwitchLine(
                            label = "只显示观众聊天",
                            desc = "过滤下注、开奖、关注、管理、下单等非聊天消息；礼物始终保留",
                            checked = c.chatOnly,
                            onChange = {
                                c.chatOnly = it
                                c.danmaku.restart(scope)
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "重连弹幕",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable { c.danmaku.restart(scope) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        // 诊断：逐个端口打一发明文 join，直接看到每个端口回了什么。
                        // 排查「一直 404」时不用再靠猜 —— 一眼能分出是端口没拿到、还是判据判错。
                        Text(
                            if (wsDiagLoading) "诊断中…" else "诊断端口",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable(enabled = !wsDiagLoading) {
                                    wsDiagLoading = true
                                    wsDiag = null
                                    scope.launch {
                                        val text = runCatching { c.live.diagnoseWs() }
                                            .getOrElse { "诊断失败：${it.message}" }
                                        wsDiag = text
                                        // 诊断时已经验过每个端口，命中的会记进候选表 —— 顺手重连一次，
                                        // 用户不用再自己点「重连弹幕」去等第二轮扫描
                                        if (text.contains("✅")) c.danmaku.restart(scope)
                                        wsDiagLoading = false
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        if (wsDiag != null) {
                            Text(
                                wsDiag!!,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(8.dp)
                            )
                        }
                    }
                }

                // ---------- 关注 ----------

                if (page == 3) {
                    // 关注说明：服务端只读 + 本机可编辑
                    Section("关注说明") {
                        val jweDesc = if (c.prefs.jwe == Prefs.Defaults.JWE) {
                            "内置默认 JWE（已就绪）"
                        } else {
                            "自定义 JWE（${c.prefs.jwe.length} 字符）"
                        }
                        InfoLine("服务端关注 ${c.serverFollowIds.size} 位 · 本机关注 ${c.follows.all().size} 位")
                        InfoLine("「服务端」是真号 1422303235 在源 App 的关注（只读，写接口走加密协议够不到）。")
                        InfoLine("「本地」是在本 App 点星标收藏的，可编辑，可复制或导出为 JSON 文件。")
                        InfoLine("点关注 = 本机立刻生效，同时后台仍试一次写接口；写不动会标「仅本机」。")
                        InfoLine("凭证：$jweDesc")
                    }

                    // 本地关注导出
                    Section("本地关注 · 导出") {
                        InfoLine("在关注页「本地」标签顶部：复制图标 = 复制 JSON 到剪贴板；保存图标 = 另存为文件（系统文件选择器，无需存储权限）。")
                        if (c.follows.all().isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                c.clearLocalFollows()
                                toast = "已清空本地关注"
                            }) { Text("清空本地关注") }
                        }
                    }
                }

                // ---------- 外观 ----------

                if (page == 4) {
                    Section {
                        ChipLine(
                            label = "主题",
                            options = listOf("跟随系统", "深色", "浅色"),
                            selected = c.themeMode,
                            onSelect = { c.themeMode = it }
                        )
                        Spacer(Modifier.height(4.dp))
                        InfoLine("游戏/棋牌/彩票导流房已在数据入口剔除，没有「显示推广位」这类开关")
                        InfoLine(
                            "附近城市：" + (c.nearbyCity.ifBlank { "自动（取房间最多的城市）" })
                        )
                        InfoLine("「附近」实际是同城：列表里没有坐标，只能按 room.city_show 筛")
                    }
                }

                // ---------- 关于 ----------

                if (page == 5) {
                    Section {
                        InfoLine("TM Live ${c.versionName}")
                        InfoLine("包名 com.tm.live · 直播流直连，不经过任何中间服务器")
                        InfoLine("直播列表直连源 App 隧道端口，数据是服务端实时值；拉列表不需要任何凭证")
                        InfoLine("播放地址来自第三方 CDN，仅供协议学习研究")
                    }
                    Spacer(Modifier.height(12.dp))
                    // ---------- 检查更新（只覆盖安装本应用）----------
                    Section("检查更新") {
                        InfoLine(
                            "只安装本应用的更新包：包名必须等于 " + ctx.packageName +
                                " 且签名一致，一键覆盖安装。登录态、设置、收藏都保留，不用卸载重装。"
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (apkScanning) return@Button
                                apkScanning = true
                                apkNote = null
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) { ApkInstaller.find(ctx) }
                                    apkScanning = false
                                    apkNote = if (found == null) {
                                        "没找到更新包：把 APK 放进下面任一目录，再点一次。"
                                    } else {
                                        ApkInstaller.install(ctx, found)
                                    }
                                }
                            },
                            enabled = !apkScanning,
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (apkScanning) "正在查找更新包…" else "扫描并覆盖安装") }
                        apkNote?.let { InfoLine(it) }
                        Spacer(Modifier.height(4.dp))
                        InfoLine(ApkInstaller.scanHint(ctx))
                        InfoLine("Android 8 以上第一次会先跳到「安装未知应用」，允许后回来再点一次。")
                    }
                }

                if (page == 6) {
                    // ---------- 小红帽会话 ----------
                    Section("小红帽会话（api.xt2.club）") {
                        InfoLine(
                            "小红帽平台默认用**演示数据**（列表/播放/聊天全链路可玩，但不是真直播）。" +
                                "把小红帽 App 的会话 token 粘到下面并关掉演示开关，即为真实接口：" +
                                "列表、拉流、聊天、接口关注全部走 api.xt2.club。"
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("演示模式（内置样例数据）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = xhmDemo,
                                onCheckedChange = {
                                    xhmDemo = it
                                    prefs.xhmDemo = it
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = xhmToken,
                            onValueChange = { xhmToken = it; prefs.xhmToken = it },
                            label = { Text("会话 token（jwt_token）") },
                            placeholder = { Text("抓包里的 jwt_token 查询参数 / Bearer 头的值") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        InfoLine(
                            if (xhmToken.isBlank()) "空 = 聊天/关注走本机与模拟，拉流接口大概率 401。"
                            else "已配置（${xhmToken.trim().length} 字符）。关注会先落本机、再打 /relation/follow 接口。"
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = xhmUserId,
                            onValueChange = { xhmUserId = it; prefs.xhmUserId = it },
                            label = { Text("用户 id（取流/关注接口用）") },
                            placeholder = { Text("小红帽账号的数字 id，可留空") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = xhmBase,
                            onValueChange = { xhmBase = it; prefs.xhmBaseUrl = it },
                            label = { Text("API 基址") },
                            placeholder = { Text("https://api.xt2.club") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        InfoLine("主域会随服务端调度变化（防封），换域名时只改这里。WS 聊天地址按它推导（https→wss）。")
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = xhmImg,
                            onValueChange = { xhmImg = it; prefs.xhmImgBase = it },
                            label = { Text("图床基址（封面/头像相对路径的补全前缀）") },
                            placeholder = { Text("留空 = 不补全，直接用接口给的地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // 强制重拉：清掉 loadedOnce 标记再走 bootstrap
                                    c.xhmReload()
                                    toast = "小红帽列表已刷新"
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("刷新小红帽列表") }
                            Button(
                                onClick = {
                                    xhmToken = ""
                                    prefs.xhmToken = ""
                                    toast = "会话已清空（回到演示数据）"
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("清空会话") }
                        }
                        InfoLine(
                            "拉流是防盗链 RTMP（约 3 分钟时效），进房时实时向接口要；" +
                                "聊天走 wss://<API基址>/ws?jwt_token=…。"
                        )
                    }
                }
            }

        }
        // 提示做浮层贴底：分页之后内容长短不一，写进 Column 尾部经常滚不到
        toast?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }


    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("粘贴房间 JSON") },
            text = {
                Column {
                    Text(
                        "支持完整数据源（含 rooms 数组），也支持直接粘 window.ROOMS=[…] 片段。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("[{\"id\":64378161,…}]") },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = c.applyPasted(text)
                    toast = err ?: "已导入 ${c.rooms.size} 个房间"
                    pasteOpen = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pasteOpen = false }) { Text("取消") }
            }
        )
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("导入实时源配置") },
            text = {
                Column {
                            Text(
                                "把配置 JSON 粘进来。天美字段：appToken（隧道/弹幕 token）、jwe、tunnelPort、" +
                                    "userId / channelId（可选）；小红帽字段放 \"xhm\" 对象：" +
                                    "token / userId / baseUrl / imgBase / demo。纯文本【会话 Token】【JWE】标记也认。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        placeholder = { Text("{ \"appToken\": …, \"jwe\": … }") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    err?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = parseRealtimeConfig(text)
                    if (cfg == null) {
                        err = "没解析出 appToken / jwe，确认粘的是导出工具的完整输出"
                        return@TextButton
                    }
                    // 输入框要跟着走，否则导完它还显示空值，看着像没导进去
                    cfg.appToken?.let { v -> prefs.appToken = v; dmToken = v }
                    cfg.jwe?.let { v -> jwe = v; prefs.jwe = v }
                    cfg.userId?.let { v -> prefs.myUserId = v }
                    cfg.channelId?.let { v -> prefs.channelId = v }
                    // 小红帽段：token/基址/图床/demo 一并落地，输入框同步，列表强刷
                    var xhmApplied = false
                    cfg.xhmToken?.let { v -> prefs.xhmToken = v; xhmToken = v; xhmApplied = true }
                    cfg.xhmUserId?.let { v -> prefs.xhmUserId = v; xhmUserId = v; xhmApplied = true }
                    cfg.xhmBaseUrl?.let { v -> prefs.xhmBaseUrl = v; xhmBase = v; xhmApplied = true }
                    cfg.xhmImgBase?.let { v -> prefs.xhmImgBase = v; xhmImg = v; xhmApplied = true }
                    cfg.xhmDemo?.let { v -> prefs.xhmDemo = v; xhmDemo = v; xhmApplied = true }
                    if (xhmApplied) c.xhmReload()
                    port = cfg.tunnelPort?.toString() ?: ""
                    prefs.tunnelPort = cfg.tunnelPort ?: 0
                    // 会话 token 换了就必须重连：旧连接是用旧 token 建的，留着也是 403
                    if (cfg.appToken != null) c.danmaku.restart(scope)
                    toast = "已导入（token/JWE" +
                        (if (cfg.userId != null) "/userId" else "") +
                        (if (cfg.channelId != null) "/channelId" else "") +
                        (if (xhmApplied) "/小红帽" else "") +
                        "），点「测试连接并刷新」验证"
                    importOpen = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { importOpen = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 卡片分组。[title] 传 null 就不画小标题 —— 二级页面里页标题已经写着了，
 * 再来一行「播放」纯属重复。
 */
@Composable
private fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        if (title != null) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            content = content
        )
    }
}

/**
 * 设置主页的一行：类别名 + 当前状态摘要 + 右箭头，整行可点。
 *
 * 摘要不是装饰 —— 隧道断没断、弹幕进没进房、关注拉到几位，直接在主页就能看到，
 * 不用挨个点进去确认。
 */
@Composable
private fun SettingEntry(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                summary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp, start = 2.dp)
    )
}

/**
 * 凭证「看得见 + 抄得走」的一行。
 *
 * 换号时得照着把老凭证抄出来留档，所以不能只写「已就绪」。默认折叠成
 * `头 28 字符…尾 12 字符`，点「展开」出全文，点「复制」进剪贴板。
 */
@Composable
private fun CredentialRow(label: String, value: String, onCopy: (String) -> Unit) {
    val cb = LocalClipboardManager.current
    var open by remember(value) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$label · ${value.length} 字符",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { open = !open },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            ) { Text(if (open) "收起" else "展开全文", fontSize = 11.sp) }
            TextButton(
                onClick = {
                    cb.setText(AnnotatedString(value))
                    onCopy("已复制 $label")
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            ) { Text("复制", fontSize = 11.sp) }
        }
        Text(
            if (open) value else value.take(28) + "…" + value.takeLast(12),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(8.dp)
        )
    }
}

@Composable
private fun ChipLine(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, name ->
                FilterChip(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    label = { Text(name, fontSize = 11.sp) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun SwitchLine(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // onCheckedChange = null：点击统一走整行的 clickable。
        // 否则 Switch 自己也会派发一次，和整行点击叠在一起就变成「点一下切两次」。
        Switch(checked = checked, onCheckedChange = null)
    }
}
