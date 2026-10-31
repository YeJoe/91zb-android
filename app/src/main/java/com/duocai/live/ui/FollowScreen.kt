package com.tm.live.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tm.live.AppContainer
import com.tm.live.data.Room
import com.tm.live.ui.theme.Gold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关注页分两类：
 *  - 服务端关注：真号 1422303235 在服务端关注的人（`/user_follow/my_follow` 的 podcast_id）。
 *    只读 —— 写接口是加密二进制协议，明文层够不到（见 docs/关注写接口-终局结论-2026-10-31.md）。
 *  - 本地关注：在本 App 里点星标收藏的人（`followJson`）。**可编辑、可复制、可导出**。
 *
 * 导出只作用于「本地关注」：复制到剪贴板，或用系统另存为（SAF）写成 JSON 文件。
 * 服务端关注是账号资产的真相源，不参与导出，也不会被本地操作改写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowScreen(c: AppContainer) {

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = LocalContext.current

    var tab by remember { mutableStateOf(0) } // 0=服务端  1=本地

    val byId = remember(c.rooms) { c.rooms.associateBy { it.id } }
    val localNames = remember(c.follows) { c.follows.all().associate { it.id to it.name } }

    LaunchedEffect(c.toast) {
        val msg = c.toast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        c.toast = null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("关注", fontSize = 21.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            // 两个分类切页
            TabChip("服务端", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("本地", tab == 1) { tab = 1 }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (!c.refreshing) scope.launch { c.refreshManually() } }) {
                if (c.refreshing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (tab == 0) {
            ServerFollowTab(c, byId, localNames)
        } else {
            LocalFollowTab(c, ctx, byId)
        }
    }
}

/** 服务端关注：只读列表，按「全部 / 开播 / 未开播」子 tab 切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFollowTab(
    c: AppContainer,
    byId: Map<Long, Room>,
    localNames: Map<Long, String>
) {
    val serverIds = c.serverFollowIds
    val names = c.serverFollowNames

    val live = ArrayList<Room>()
    val offline = ArrayList<Room>()
    for (id in serverIds) {
        val hit = byId[id]
        val name = names[id] ?: localNames[id] ?: "房间 $id"
        val room = hit ?: Room(id, name, "", 0, "", false, 0)
        if (hit != null && !hit.ad && hit.liveIn) live.add(room) else offline.add(room)
    }
    live.sortByDescending { it.online }
    val liveIds = remember(live) { live.map { it.id }.toSet() }

    var sub by remember { mutableStateOf(0) } // 0 全部 / 1 开播 / 2 未开播
    val shown = remember(sub, live, offline) {
        when (sub) {
            1 -> live
            2 -> offline
            else -> (live + offline)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (serverIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubTabChip("全部 ${live.size + offline.size}", sub == 0) { sub = 0 }
                Spacer(Modifier.width(6.dp))
                SubTabChip("开播 ${live.size}", sub == 1) { sub = 1 }
                Spacer(Modifier.width(6.dp))
                SubTabChip("未开播 ${offline.size}", sub == 2) { sub = 2 }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (serverIds.isEmpty()) {
                RefreshFill {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("服务端还没有关注", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "这是真号 1422303235 在源 App 的关注（共 ${serverIds.size} 位）。\n点右上角刷新从服务端拉取。",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when (sub) {
                            1 -> "暂无正在直播的关注"
                            2 -> "未开播列表为空"
                            else -> "暂无关注"
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(shown, key = { "sl-${it.id}" }) { room ->
                        val isLive = room.id in liveIds
                        FollowRow(c, room, isLive, false) { onPlay(c, shown, room) }
                    }
                    item {
                        Text(
                            "服务端关注只读，无法在本 App 取消（写接口走加密协议）。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 本地关注：可编辑 + 复制/导出，按「全部 / 开播 / 未开播」子 tab 切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalFollowTab(
    c: AppContainer,
    ctx: Context,
    byId: Map<Long, Room>
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list = remember(c.follows) { c.follows.all() }

    val live = remember(list, byId) { list.filter { byId[it.id]?.let { r -> !r.ad && r.liveIn } == true } }
    val offline = remember(list, byId) { list.filter { byId[it.id]?.let { r -> !r.ad && r.liveIn } != true } }
    val liveIds = remember(live) { live.map { it.id }.toSet() }

    var sub by remember { mutableStateOf(0) } // 0 全部 / 1 开播 / 2 未开播
    val shown = remember(sub, list, live, offline) {
        when (sub) {
            1 -> live
            2 -> offline
            else -> list
        }
    }

    // SAF「另存为」：用户自己挑目录和文件名，App 不需要任何存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = c.exportFollowsJson()
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("无法打开输出流")
                c.toast = "已导出本地关注 JSON（${list.size} 条）"
            }.onFailure { e ->
                c.toast = "导出失败：${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 本地导出条：复制到剪贴板 / 另存为 JSON 文件（无 GitHub 依赖）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val json = c.exportFollowsJson()
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("follows", json))
                android.widget.Toast.makeText(ctx, "已复制 ${list.size} 条本地关注 JSON", android.widget.Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制 JSON", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                exportLauncher.launch("tm_live_follows_$stamp.json")
            }) {
                Icon(Icons.Filled.Save, contentDescription = "导出到本地文件", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${list.size} 位本地关注 · 可复制/导出",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        if (list.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubTabChip("全部 ${list.size}", sub == 0) { sub = 0 }
                Spacer(Modifier.width(6.dp))
                SubTabChip("开播 ${live.size}", sub == 1) { sub = 1 }
                Spacer(Modifier.width(6.dp))
                SubTabChip("未开播 ${offline.size}", sub == 2) { sub = 2 }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("还没有本地关注", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在首页/直播页点封面右上角星标就会收藏到这里。\n点上方按钮可复制或导出 JSON。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when (sub) {
                            1 -> "暂无正在直播的本地关注"
                            2 -> "未开播列表为空"
                            else -> "暂无本地关注"
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown, key = { "lo-${it.id}" }) { f ->
                        val room = byId[f.id] ?: Room(f.id, f.name, f.cat, 0, "", false, 0)
                        val isLive = f.id in liveIds
                        FollowRow(c, room, isLive, true) {
                            val playlist = shown.map { byId[it.id] ?: Room(it.id, it.name, it.cat, 0, "", false, 0) }
                            onPlay(c, playlist, room)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 关注页内「全部 / 开播 / 未开播」子切换卡（紧凑版 TabChip） */
@Composable
private fun SubTabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

private fun onPlay(c: AppContainer, list: List<Room>, room: Room) {
    c.openPlayer(list, list.indexOfFirst { it.id == room.id }.coerceAtLeast(0))
}

@Composable
private fun FollowRow(
    c: AppContainer,
    room: Room,
    live: Boolean,
    removable: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .alpha(if (live) 1f else 0.62f)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (live) Modifier.background(avBrush(room.id))
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .alpha(if (live) 1f else 0.6f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initialOf(room.name),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (live) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (room.avatar.isNotEmpty()) {
                val ctx = LocalContext.current
                val req = remember(room.avatar) {
                    ImageRequest.Builder(ctx).data(room.avatar).crossfade(150).build()
                }
                AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (live) "${room.cat} · ${fmtCount(room.online)} 人在看 · ID ${room.id}"
                else "未开播 · ID ${room.id}",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (removable) {
            IconButton(onClick = { c.unfollow(room.id) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Star, contentDescription = "取消关注", tint = Gold, modifier = Modifier.size(16.dp))
            }
        } else {
            Text("源App", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}
