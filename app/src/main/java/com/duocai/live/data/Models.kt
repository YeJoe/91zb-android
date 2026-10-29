package com.duocai.live.data

import org.json.JSONArray
import org.json.JSONObject

/** 一个直播间。字段刻意压到最少 —— 接口里那些 avatar/title/chess/battle 在 App 里都用不上。 */
data class Room(
    val id: Long,
    val name: String,
    val cat: String,
    val online: Int,
    val city: String,
    val ad: Boolean,
    val coins: Int
) {
    /** rtmp 流名就是房间号，这一点已用 ffprobe 实测过（见 docs/直播流地址.md） */
    fun rtmpUrl(domain: String): String? {
        val d = domain.trim().removePrefix("rtmp://").removePrefix("http://").trimEnd('/')
        if (d.isEmpty()) return null
        return "rtmp://$d/live/$id"
    }
}

data class Cat(val name: String, val count: Int)

data class SourceMeta(
    val buildAt: String = "",
    val rtmpDomain: String = "",
    val cats: List<Cat> = emptyList(),
    val fetchedAt: Long = 0L,
    val fromCache: Boolean = false
)

data class SourceData(val rooms: List<Room>, val meta: SourceMeta)

data class Follow(
    val id: Long,
    val name: String,
    val cat: String,
    val ts: Long
)

/**
 * 数据源解析。用 org.json（系统自带）而不是 kotlinx-serialization：
 * 结构很简单，省一个编译器插件、省 ~200KB、也省掉 R8 与反射的那堆坑。
 */
object SourceParser {

    fun parse(text: String): SourceData? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = obj.optJSONArray("rooms") ?: return null
        val rooms = readRooms(arr)
        if (rooms.isEmpty()) return null
        return SourceData(rooms, SourceMeta(
            buildAt = obj.optString("buildAt"),
            rtmpDomain = obj.optString("rtmpDomain"),
            cats = readCats(obj.optJSONArray("cats"))
        ))
    }

    /**
     * 允许直接粘网页版 data.js 的片段（`window.ROOMS=[{...}];`），
     * 免得用户为了填数据源还要自己拼一层 {"rooms": ...}。
     */
    fun parseLoose(raw: String): SourceData? {
        val t = raw.trim()
        parse(t)?.let { return it }
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val inner = t.substring(start, end + 1)
        return runCatching {
            val rooms = readRooms(JSONArray(inner))
            if (rooms.isEmpty()) null else SourceData(rooms, SourceMeta())
        }.getOrNull()
    }

    private fun readRooms(arr: JSONArray): List<Room> {
        val out = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optLong("id", 0L)
            if (id <= 0L) continue
            out.add(
                Room(
                    id = id,
                    name = r.optString("name").ifBlank { "房间 $id" },
                    cat = r.optString("cat").ifBlank { "其他" },
                    online = r.optInt("online", 0),
                    city = r.optString("city").let { if (it == "火星") "" else it },
                    ad = r.optInt("ad", 0) == 1,
                    coins = r.optInt("coins", 0)
                )
            )
        }
        return out
    }

    private fun readCats(arr: JSONArray?): List<Cat> {
        if (arr == null) return emptyList()
        val out = ArrayList<Cat>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(Cat(c.optString("name"), c.optInt("count", 0)))
        }
        return out
    }
}
