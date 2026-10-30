package com.duocai.live.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地收藏 = 关注的可靠镜像。
 *
 * 点关注时 `AppContainer.toggleFollow` 会先改这里（界面立刻变金星标），
 * 再后台去打真实接口 `LiveRepo.syncFollow()`（/user_follow/add）。
 * 实测本机明文隧道上写接口是空转（见 docs/App实现现状.md §7.2），所以本地收藏
 * 是"一定生效"的那一份；服务端关注以 `my_follow` 复查结果为准，没确认也不影响本地。
 */
class FollowStore(private val prefs: Prefs) {

    private val map = LinkedHashMap<Long, Follow>()

    init {
        load()
    }

    private fun load() {
        map.clear()
        val raw = prefs.followJson
        if (raw.isBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                if (id <= 0L) continue
                map[id] = Follow(
                    id = id,
                    name = o.optString("name"),
                    cat = o.optString("cat"),
                    ts = o.optLong("ts", 0L)
                )
            }
        }.onFailure { map.clear() }
    }

    private fun save() {
        val arr = JSONArray()
        for (f in map.values) {
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("cat", f.cat)
                put("ts", f.ts)
            })
        }
        prefs.followJson = arr.toString()
    }

    fun ids(): Set<Long> = map.keys.toSet()

    fun isFollowed(id: Long): Boolean = map.containsKey(id)

    fun all(): List<Follow> = map.values.sortedByDescending { it.ts }

    /** 返回操作后是否处于"已关注" */
    fun toggle(room: Room): Boolean {
        val existed = map.remove(room.id) != null
        if (!existed) {
            map[room.id] = Follow(room.id, room.name, room.cat, System.currentTimeMillis())
        }
        save()
        return !existed
    }

    fun remove(id: Long) {
        if (map.remove(id) != null) save()
    }
}
