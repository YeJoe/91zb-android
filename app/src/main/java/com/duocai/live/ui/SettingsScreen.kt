package com.duocai.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duocai.live.AppContainer
import com.duocai.live.data.Prefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(c: AppContainer) {

    val scope = rememberCoroutineScope()
    val prefs = c.prefs

    var url by remember { mutableStateOf(prefs.roomsUrl) }
    var domain by remember { mutableStateOf(prefs.rtmpDomain) }
    var gateway by remember { mutableStateOf(prefs.hlsGateway) }
    var ws by remember { mutableStateOf(prefs.dmWs) }
    var token by remember { mutableStateOf(prefs.dmToken) }
    var pasteOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

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

        // ---------- 数据源 ----------
        Section("数据源") {
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
                            c.reload()
                            toast = c.error ?: "已更新 ${c.rooms.size} 个房间"
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (c.loading) "拉取中…" else "立即拉取") }
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
            Spacer(Modifier.height(4.dp))
            InfoLine("房间 ${c.rooms.size} 个" + if (c.meta.fromCache) " · 本地缓存" else "")
            if (c.meta.buildAt.isNotBlank()) InfoLine("快照时间 ${c.meta.buildAt}")
            if (c.error != null) InfoLine("上次错误：${c.error}")
        }

        // ---------- 播放 ----------
        Section("播放") {
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it; prefs.rtmpDomain = it },
                label = { Text("RTMP 域名") },
                placeholder = { Text("xxx.hebeisaixin.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            InfoLine("播放地址 = rtmp://<域名>/live/<房间号>，不需要 token")
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
            ChipLine(
                label = "画面缩放",
                options = listOf("适应", "裁剪", "拉伸"),
                selected = prefs.scaleMode,
                onSelect = { prefs.scaleMode = it }
            )
            Spacer(Modifier.height(8.dp))
            val buffers = listOf(800, 1500, 3000)
            ChipLine(
                label = "缓冲",
                options = buffers.map { "${it / 1000.0}s" },
                selected = buffers.indexOf(prefs.bufferMs).let { if (it < 0) 1 else it },
                onSelect = { prefs.bufferMs = buffers[it]; c.player.setBuffer(buffers[it]) }
            )
            InfoLine("缓冲档位下次启动生效（解码器只在创建时读一次）")
            Spacer(Modifier.height(4.dp))
            SwitchLine(
                label = "进房自动播放",
                desc = "关掉就先进暂停态，省流量",
                checked = prefs.autoPlay,
                onChange = { prefs.autoPlay = it }
            )
        }

        // ---------- 弹幕 ----------
        Section("弹幕") {
            OutlinedTextField(
                value = ws,
                onValueChange = { ws = it; prefs.dmWs = it },
                label = { Text("WebSocket 端点（可选）") },
                placeholder = { Text("ws://…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            InfoLine("留空则回放内置的真实弹幕样本（离线演示）")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; prefs.dmToken = it },
                label = { Text("鉴权 Token（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            InfoLine("用 Android Keystore 加密后存本机，不进仓库也不进 APK")
            Spacer(Modifier.height(4.dp))
            SwitchLine(
                label = "只显示观众聊天",
                desc = "过滤下注、开奖、送礼、关注、管理、下单等非聊天消息",
                checked = c.chatOnly,
                onChange = {
                    c.chatOnly = it
                    c.danmaku.restart(scope)
                }
            )
        }

        // ---------- 关注 ----------
        Section("关注") {
            InfoLine("已关注 ${c.followedIds.size} 位 · 存在本机，不依赖账号")
            InfoLine("服务端关注接口公网不可达（详见 docs/安卓App设计方案.md §3.3），暂不做同步")
            if (c.followedIds.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    c.followedIds.toList().forEach { c.unfollow(it) }
                    toast = "已清空关注"
                }) { Text("清空关注列表") }
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
            SwitchLine(
                label = "显示推广位",
                desc = "默认关掉：这类房间基本是博彩导流",
                checked = c.showAd,
                onChange = { c.showAd = it }
            )
        }

        // ---------- 关于 ----------
        Section("关于") {
            InfoLine("DC Live ${c.versionName}")
            InfoLine("包名 com.duocai.live · 直播流直连，不经过任何中间服务器")
            InfoLine("数据为快照，非实时列表；播放地址来自第三方 CDN，仅供协议学习研究")
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
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
