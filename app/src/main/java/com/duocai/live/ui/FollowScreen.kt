package com.tm.live.ui

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

/**
 * 关注页分两类：
 *  - 服务端关注：真号 1422303235 在服务端关注的人（`/user_follow/my_follow` 的 podcast_id）。
 *    只读 —— 写接口是加密二进制协议，明文层够不到（见 docs/关注写接口-终局结论-2026-10-31.md）。
 *  - 本地关注：在本 App 里点星标收藏的人（`followJson`）。**可编辑、可导出、可同步**。
 *
 * 同步只作用于「本地关注」：把这份收藏推到 GitHub Gist（raw 可读），跨设备收敛。
 * 服务端关注是账号资产的真相源，不参与同步，也不会被本地操作改写。
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

/** 服务端关注：只读列表 */
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

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = c.refreshing,
            onRefresh = { /* 由右上角统一触发 */ },
            modifier = Modifier.fillMaxSize()
        ) {
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
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (live.isNotEmpty()) {
                        item { SectionTitle("正在直播 · ${live.size}", true) }
                        items(live, key = { "sl-${it.id}" }) { room ->
                            FollowRow(c, room, true, false) { onPlay(c, live, room) }
                        }
                    }
                    if (offline.isNotEmpty()) {
                        item { SectionTitle("未开播 · ${offline.size}", false) }
                        items(offline, key = { "so-${it.id}" }) { room ->
                            FollowRow(c, room, false, false) { }
                        }
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

/** 本地关注：可编辑 + 同步 + 导出 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalFollowTab(
    c: AppContainer,
    ctx: Context,
    byId: Map<Long, Room>
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list = remember(c.follows) { c.follows.all() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 同步控制条
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { c.syncNow() }, enabled = !c.syncBusy) {
                    if (c.syncBusy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Icon(Icons.Filled.CloudDone, contentDescription = "同步", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { c.pushNow() }, enabled = !c.syncBusy) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = "上传", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val json = c.exportFollowsJson()
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("follows", json))
                    android.widget.Toast.makeText(ctx, "已复制 ${list.size} 条本地关注 JSON", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "导出", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (c.prefs.ghToken.isBlank()) "未设置 Token → 设置页填" else (c.syncMsg.ifBlank { "未同步" }),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (list.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.height(10.dp))
                                Text("还没有本地关注", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "在首页/直播页点封面右上角星标就会收藏到这里。\n点「同步」可上传到 GitHub Gist 多设备共享。",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    item { SectionTitle("本地收藏 · ${list.size}", false) }
                    items(list, key = { "lo-${it.id}" }) { f ->
                        val room = byId[f.id]
                        FollowRow(
                            c,
                            room ?: Room(f.id, f.name, f.cat, 0, "", false, 0),
                            room != null && room.liveIn,
                            true
                        ) { if (room != null) onPlay(c, list.map { byId[it.id] ?: Room(it.id, it.name, it.cat, 0, "", false, 0) }, room) }
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

private fun onPlay(c: AppContainer, list: List<Room>, room: Room) {
    c.openPlayer(list, list.indexOfFirst { it.id == room.id }.coerceAtLeast(0))
}

@Composable
private fun SectionTitle(text: String, live: Boolean) {
    Row(
        Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(Modifier.size(6.dp).background(Color(0xFFFF3B5C), CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
            .clickable(enabled = live, onClick = onClick)
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
                color = MaterialTheme.colorScheme.onSurface,
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
