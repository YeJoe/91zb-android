package com.duocai.live.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 关注 = 本地收藏。
 *
 * 服务端的 /relationship/followees/ 接口确实存在，但三道门都过不去：
 * 公网不可达、需要真实登录态、原来的游客 token 也不在了（详见设计方案 §3.3）。
 * 所以关注关系存在本机，拿房间号去当前快照里比对"在不在播"，这条路立刻能用。
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
