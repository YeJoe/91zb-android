package com.duocai.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duocai.live.data.DanmakuStore
import com.duocai.live.data.FollowStore
import com.duocai.live.data.Prefs
import com.duocai.live.data.Room
import com.duocai.live.data.RoomRepo
import com.duocai.live.data.SourceData
import com.duocai.live.data.SourceMeta
import com.duocai.live.player.PlayerHolder

enum class Tab(val label: String) {
    HOME("首页"), FOLLOW("关注"), SETTINGS("设置")
}

/**
 * 手写的依赖容器（不用 Hilt：省一个注解处理器、省 ~800KB、也省一堆 KSP 版本对齐）。
 *
 * 顺便承担了"进程内状态"的职责 —— 这些状态挂在 Application 上，
 * 横竖切换、Activity 重建都不会丢，方案里那堆 ViewModel 就一起省了。
 */
class AppContainer(context: Context) {

    val context: Context = context.applicationContext

    val prefs = Prefs(this.context)
    val repo = RoomRepo(this.context, prefs)
    val follows = FollowStore(prefs)
    val danmaku = DanmakuStore(this.context, prefs)
    val player = PlayerHolder(this.context)

    val versionName: String = runCatching {
        this.context.packageManager
            .getPackageInfo(this.context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---- 数据 ----
    var rooms by mutableStateOf<List<Room>>(emptyList())
    var meta by mutableStateOf(SourceMeta())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedOnce by mutableStateOf(false)

    // ---- 导航 ----
    var tab by mutableStateOf(Tab.HOME)
    /** 当前播放页的房间序列（列表可能被分类/搜索筛过，翻页要跟着筛过的走） */
    var playlist by mutableStateOf<List<Room>>(emptyList())
    var openIndex by mutableStateOf(-1)

    // ---- 关注（ids 是快照，供 Compose 观察）----
    var followedIds by mutableStateOf<Set<Long>>(emptySet())

    // ---- 设置里会被界面直接读的几项，做成可观察 ----
    private var showAdState by mutableStateOf(prefs.showAd)
    var showAd: Boolean
        get() = showAdState
        set(v) {
            showAdState = v
            prefs.showAd = v
        }

    private var themeState by mutableStateOf(prefs.themeMode)
    var themeMode: Int
        get() = themeState
        set(v) {
            themeState = v
            prefs.themeMode = v
        }

    private var chatOnlyState by mutableStateOf(prefs.chatOnly)
    var chatOnly: Boolean
        get() = chatOnlyState
        set(v) {
            chatOnlyState = v
            prefs.chatOnly = v
        }

    fun openPlayer(list: List<Room>, index: Int) {
        if (list.isEmpty()) return
        playlist = list
        openIndex = index.coerceIn(0, list.size - 1)
    }

    fun closePlayer() {
        openIndex = -1
        player.pause()
    }

    fun toggleFollow(room: Room) {
        follows.toggle(room)
        followedIds = follows.ids()
    }

    fun unfollow(id: Long) {
        follows.remove(id)
        followedIds = follows.ids()
    }

    fun followedList(): List<Room> {
        val byId = rooms.associateBy { it.id }
        return follows.all().mapNotNull { byId[it.id] }
    }

    /** 冷启动：先出缓存，再拉网络 */
    suspend fun bootstrap() {
        if (loadedOnce) return
        loadedOnce = true
        followedIds = follows.ids()
        repo.loadCache()?.let { applyData(it, fromCache = true) }
        reload()
    }

    suspend fun reload() {
        loading = true
        error = null
        val result = repo.fetch()
        loading = false
        result.onSuccess { applyData(it, fromCache = false) }
            .onFailure { e ->
                error = e.message ?: "数据源加载失败"
                if (rooms.isEmpty()) {
                    repo.loadCache()?.let { applyData(it, fromCache = true) }
                }
            }
    }

    fun applyPasted(text: String): String? {
        return repo.applyPasted(text)
            .onSuccess { applyData(it, fromCache = false) }
            .exceptionOrNull()?.message
    }

    private fun applyData(data: SourceData, fromCache: Boolean) {
        rooms = data.rooms
        meta = data.meta.copy(fetchedAt = System.currentTimeMillis(), fromCache = fromCache)
    }
}
