package com.tm.live.data.xhm

import com.tm.live.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小红帽本地收藏 = 小红帽「关注」的可靠镜像。
 *
 * 与天美的 [com.tm.live.data.FollowStore] 同构：点关注先落这里（界面立刻变），
 * 服务端写接口（`/relation/follow` 之类，需会话 token）通了之后再补打。
 * demo 模式 / 无 token 阶段，这份本机收藏就是「关注列表」页的唯一数据源。
 */
class XhmFollowStore(private val prefs: Prefs) {

    private val map = LinkedHashMap<Long, XhmFollow>()

    init { load() }

    private fun load() {
        map.clear()
        val raw = prefs.xhmFollowJson
        if (raw.isBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                if (id <= 0L) continue
                map[id] = XhmFollow(
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
        prefs.xhmFollowJson = arr.toString()
    }

    fun ids(): Set<Long> = map.keys.toSet()

    fun isFollowed(id: Long): Boolean = map.containsKey(id)

    /** 关注页用：按收藏时间倒序的房间 id 集合，由调用方去列表里对出房间 */
    fun all(): List<XhmFollow> = map.values.sortedByDescending { it.ts }

    /** 显式设置关注状态（幂等），返回操作后是否处于"已关注" */
    fun setFollowed(room: XhmRoom, followed: Boolean): Boolean {
        if (followed) {
            map[room.id] = XhmFollow(room.id, room.name, room.cat, System.currentTimeMillis())
        } else {
            map.remove(room.id)
        }
        save()
        return followed
    }

    fun remove(id: Long) {
        if (map.remove(id) != null) save()
    }
}
