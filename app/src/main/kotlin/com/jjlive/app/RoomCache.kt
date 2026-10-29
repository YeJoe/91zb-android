package com.jjlive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 首页房间快照缓存。
 *
 * 冷启动时先用上次成功拉到的列表把界面填满（毫秒级），再在后台请求最新数据覆盖，
 * 避免每次进 App 都要盯着空白等网络。
 */
object RoomCache {

    private const val FILE = "rooms_snapshot.json"
    private const val MAX_CACHE = 400

    private fun file(ctx: Context) = File(ctx.cacheDir, FILE)

    fun load(ctx: Context): List<Room> {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Room>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> toRoom(o)?.let { out.add(it) } }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, rooms: List<Room>) {
        if (rooms.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            rooms.take(MAX_CACHE).forEach { arr.put(fromRoom(it)) }
            val target = file(ctx)
            val tmp = File(ctx.cacheDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    fun size(ctx: Context): Long = runCatching { file(ctx).length() }.getOrDefault(0L)

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun fromRoom(r: Room) = JSONObject().apply {
        put("roomId", r.roomId)
        put("userId", r.userId)
        put("userName", r.userName)
        put("roomName", r.roomName)
        put("hot", r.hot)
        put("cover", r.cover)
        put("liveType", r.liveType)
        put("payType", r.payType)
        put("moneyFen", r.moneyFen)
        put("oneToOneState", r.oneToOneState)
        put("url0", r.url0)
        put("url360", r.url360)
        put("url720", r.url720)
        put("cats", JSONArray(r.cats))
        put("anchorType", r.anchorType)
        put("gameRoom", r.gameRoom)
        put("liveState", r.liveState)
        put("stateStr", r.stateStr)
    }

    private fun toRoom(o: JSONObject): Room? {
        val id = o.optString("roomId")
        if (id.isBlank()) return null
        val cats = o.optJSONArray("cats")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        return Room(
            roomId = id,
            userId = o.optString("userId"),
            userName = o.optString("userName"),
            roomName = o.optString("roomName"),
            hot = o.optInt("hot"),
            cover = o.optString("cover"),
            liveType = o.optInt("liveType", 1),
            payType = o.optInt("payType"),
            moneyFen = o.optInt("moneyFen"),
            oneToOneState = o.optInt("oneToOneState", 1),
            url0 = o.optString("url0"),
            url360 = o.optString("url360"),
            url720 = o.optString("url720"),
            cats = cats,
            anchorType = o.optInt("anchorType", 2),
            gameRoom = o.optInt("gameRoom"),
            liveState = o.optInt("liveState", 1),
            stateStr = o.optString("stateStr")
        )
    }
}
