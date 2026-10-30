package com.duocai.live.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duocai.live.AppContainer
import com.duocai.live.data.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 解析导出工具给的配置；appToken / jwe 任一有值即视为有效。 */
private data class RealtimeConfig(
    val appToken: String?,
    val jwe: String?,
    val tunnelPort: Int?
)

/**
 * 解析导出工具的配置：先试 JSON（{"appToken":..,"jwe":..,"tunnelPort":..}），
 * 再退回纯文本标记（含【会话 Token】等）。返回 null = 啥也没解析出来。
 */
private fun parseRealtimeConfig(text: String): RealtimeConfig? {
    val s = text.trim()
    runCatching { JSONObject(s) }.getOrNull()?.let { j ->
        val t = j.optString("appToken").takeIf { it.isNotBlank() }
        val w = j.optString("jwe").takeIf { it.isNotBlank() }
        val p = j.optInt("tunnelPort", -1).let { if (it > 0) it else null }
        if (t != null || w != null) return RealtimeConfig(t, w, p)
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
    /** 「备用数据源」默认收起：实时源正常时这组根本不用碰 */
    var advancedOpen by remember { mutableStateOf(false) }

    // 直播源（直连源 App 隧道端口）
    // JWE 已内置默认值（Prefs.Defaults.JWE），输入框就留空 —— 直接把 330 字符塞进
    // 单行输入框，用户只会看到一串乱码似的尾巴。要看/改时才自己粘一份进来。
    var jwe by remember {
        mutableStateOf(prefs.jwe.takeIf { it != Prefs.Defaults.JWE } ?: "")
    }
    var port by remember { mutableStateOf(if (prefs.tunnelPort > 0) prefs.tunnelPort.toString() else "") }

    // 内置隧道
    var appKey by remember { mutableStateOf(prefs.kiwiAppKey) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Text(
            "设置",
            fontSize = 21.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
        )

        // ---------- 内置隧道 ----------
        Section("内置隧道（不再需要源 App）") {
            val t = c.tunnel
            val dot = when (t.state) {
                com.duocai.live.tunnel.Tunnel.State.READY -> Color(0xFF35D07F)
                com.duocai.live.tunnel.Tunnel.State.STARTING -> Color(0xFFFFC24B)
                com.duocai.live.tunnel.Tunnel.State.FAILED -> Color(0xFFFF5C7A)
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
                    enabled = t.state != com.duocai.live.tunnel.Tunnel.State.STARTING
                ) {
                    Text(
                        when (t.state) {
                            com.duocai.live.tunnel.Tunnel.State.STARTING -> "握手…"
                            com.duocai.live.tunnel.Tunnel.State.READY -> "已就绪"
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
                    enabled = t.state != com.duocai.live.tunnel.Tunnel.State.STARTING
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
                    enabled = t.state != com.duocai.live.tunnel.Tunnel.State.STARTING
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
            // 探活结果：判断「列表里全是死房」到底是房间不对还是域名/网络不对
            if (c.probeInfo.isNotBlank()) {
                InfoLine("在播探活：${c.probeInfo}")
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

        // ---------- 播放 ----------
        Section("播放") {
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
            SwitchLine(
                label = "切后台自动浮窗",
                desc = "播放中按 Home 缩成小窗继续看；关掉就只能点播放页顶栏的「浮窗」",
                checked = c.autoPip,
                onChange = { c.autoPip = it }
            )
        }

        // ---------- 弹幕 ----------
        Section("弹幕") {
            val dmState = c.danmaku.status.ifBlank { "未开始" }
            InfoLine("走隧道里的 `/websock/…`：join 进房后只收实时 room_msg，不补历史 —— 没人说话就空着")
            InfoLine("链路：$dmState · 本次已收 ${c.danmaku.liveCount} 条")
            // 弹幕只能走业务 nginx 那个 vhost；列表用的端口能握手但 /websock/… 会 404
            InfoLine("端口：im=${c.tunnel.imPort} · 列表=${c.tunnel.port}（为 0 表示隧道没起）")
            val tried = c.danmaku.ports
            InfoLine(
                if (tried.isEmpty()) "弹幕候选端口：还没开始试"
                else "弹幕候选端口（按序试 join）：${tried.joinToString(" → ")}"
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
        }

        // ---------- 关注 ----------
        Section("关注") {
            val jweDesc = if (c.prefs.jwe == Prefs.Defaults.JWE) {
                "内置默认 JWE（已就绪）"
            } else {
                "自定义 JWE（${c.prefs.jwe.length} 字符）"
            }
            InfoLine("本地收藏 ${c.followedIds.size} 位 · 服务端关注 ${c.serverFollowIds.size} 位")
            InfoLine("服务端关注走 `/user_follow/my_follow`，凭证：$jweDesc")
            InfoLine("两个来源合并展示，「关注」页里在播的排前面，未开播的标「未开播」")
            if (c.followedIds.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    c.followedIds.toList().forEach { c.unfollow(it) }
                    toast = "已清空关注"
                }) { Text("清空本地收藏") }
            }
        }

        // ---------- 外观 ----------
        Section("外观") {
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

        // ---------- 关于 ----------
        Section("关于") {
            InfoLine("TM Live ${c.versionName}")
            InfoLine("包名 com.duocai.live · 直播流直连，不经过任何中间服务器")
            InfoLine("直播列表直连源 App 隧道端口，数据是服务端实时值；拉列表不需要任何凭证")
            InfoLine("播放地址来自第三方 CDN，仅供协议学习研究")
        }

        toast?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp)
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
                        "把导出工具给的整段文本或 JSON 粘进来。只有 JWE 有用，Token 现在用不到" +
                            "（拉直播间列表实测零凭证），带了也会存下来但不上屏。",
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
                    cfg.appToken?.let { v -> prefs.appToken = v }
                    cfg.jwe?.let { v -> jwe = v; prefs.jwe = v }
                    port = cfg.tunnelPort?.toString() ?: ""
                    prefs.tunnelPort = cfg.tunnelPort ?: 0
                    toast = "已导入，点「测试连接并刷新」验证"
                    importOpen = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { importOpen = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
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

@Composable
private fun InfoLine(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp, start = 2.dp)
    )
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
