package com.duocai.live.data

import org.json.JSONArray
import org.json.JSONObject

/** 播放通道。实测下来 HTTP-FLV 最稳，RTMP 只当备选。 */
enum class StreamMode {
    /** 自动：先 FLV，失败自动换 RTMP */
    AUTO,
    /** 只用 HTTP-FLV（http://域名/live/房间号.flv） */
    FLV,
    /** 只用 RTMP（rtmp://域名/live/房间号） */
    RTMP;

    companion object {
        fun of(value: Int): StreamMode = when (value) {
            1 -> FLV
            2 -> RTMP
            else -> AUTO
        }
    }
}

/** 一个直播间。字段刻意压到最少 —— 接口里那些 avatar/title/chess/battle 在 App 里都用不上。 */
data class Room(
    val id: Long,
    val name: String,
    val cat: String,
    val online: Int,
    val city: String,
    val ad: Boolean,
    val coins: Int,
    /** 服务端分类 id（对应 lobby_menu 表）：101 星秀 / 102 女神 / 103 颜值 / 104 交友 / 105 音乐 / 108 舞蹈 */
    val menuId: Int = 0,
    /**
     * 封面图完整 URL（头像放大用）。
     *
     * 接口给的是相对路径 `/static/upload/u/…/head/xxx.jpg`，真正的图床是 CloudFront；
     * 抓包里 116 次图片请求全是 `GET /images/256<相对路径去掉 /static/upload>`，
     * 实测把尺寸改成 480 也能出图（61365 字节，是原图上限）。所以拼接规则是：
     *
     *     https://<图床>/images/480 + attachment_url.removePrefix("/static/upload")
     *
     * 空串 = 没有图，UI 退回渐变卡 + 名字首字。
     */
    val avatar: String = ""
) {
    /**
     * 拉流地址候选列表（按优先级）。
     *
     * 2026-10-29 实测结论：
     * - **HTTP-FLV 最稳**：`http://域名/live/<房间号>.flv` → 200 + 真 FLV（`FLV\x01` 头），h264 720x1280 + aac。
     *   同一台服务器上 `.m3u8` 也返回 FLV（nginx 没做 HLS），所以 HLS 这条路不存在。
     * - **RTMP 裸地址不需要 token**：`rtmp://域名/live/<房间号>` 用 ffprobe 能拉通；
     *   抓包里的 `?token=…&t=…&liteavToken=…` 是服务端生成的可选参数，不是鉴权必需。
     * - 但 App 里的 antmedia/librtmp 连这个 CDN 会抛 `RTMP error: -14`（握手不兼容），
     *   所以默认顺序是 FLV → RTMP，前一个失败自动换下一个。
     */
    fun streamUrls(domain: String, mode: StreamMode): List<String> {
        val raw = domain.trim().trimEnd('/')
        if (raw.isEmpty()) return emptyList()
        val scheme = if (raw.startsWith("https://")) "https" else "http"
        val bare = raw.removePrefix("rtmp://").removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
        if (bare.isEmpty()) return emptyList()
        val flv = "$scheme://$bare/live/$id.flv"
        val rtmp = "rtmp://$bare/live/$id"
        return when (mode) {
            StreamMode.FLV -> listOf(flv)
            StreamMode.RTMP -> listOf(rtmp)
            StreamMode.AUTO -> listOf(flv, rtmp)
        }
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
            val name = r.optString("name").ifBlank { "房间 $id" }
            val cat = r.optString("cat").ifBlank { "其他" }
            // 离线 JSON 没有 menu_id，只能拿 cat 当分区、拿房名当话术判据（判据同 LiveRepo.toRoom）
            if (r.optInt("ad", 0) == 1) continue
            if (cat == "游戏") continue
            if (NonHuman.bannedName(name)) continue
            out.add(
                Room(
                    id = id,
                    name = name,
                    cat = cat,
                    online = r.optInt("online", 0),
                    city = r.optString("city").let { if (it == "火星") "" else it },
                    ad = r.optInt("ad", 0) == 1,
                    coins = r.optInt("coins", 0),
                    // JSON 数据源没有图床配置可拼，只认完整 URL；加密形态统一换成明文镜像
                    avatar = ImageUrls.plain(
                        r.optString("avatar").takeIf { it.startsWith("http") } ?: ""
                    )
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
