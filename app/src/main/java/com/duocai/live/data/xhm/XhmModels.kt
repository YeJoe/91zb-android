package com.tm.live.data.xhm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 小红帽数据模型。
 *
 * 和天美那边的 [com.tm.live.data.Room] 平行，但字段按小红帽接口文档（`接口清单_小红帽1.17.1.md`）
 * 重新定义：小红帽用的是 `api.xt2.club` 的 REST（`/home`、`/relation/followlist`、
 * `/webrtc/v1/pullstream`），拉流是防盗链 RTMP，聊天走 `wss://api.xt2.club/ws?jwt_token=`。
 *
 * 接口文档里只拿到了路由（GET/POST 路径），**没有**抓到精确的 JSON 响应体结构，
 * 所以解析是"防御式"的：能认出常见字段就认，认不出就退回到只保留能拿来播的字段。
 * 真要 100% 贴合，需 jadx 反编译 `classes*.dex` 里的 `*Api`/`*Service` 看 Retrofit 注解。
 */

/** 直播间。字段刻意压到最少：列表/播放/关注都要用。 */
data class XhmRoom(
    val id: Long,
    val name: String,
    val cat: String,
    val online: Int,
    val avatar: String = "",
    val cover: String = "",
    val fans: Int = 0,
    /** 拉流地址（RTMP 防盗链，约 3 分钟时效）。空 = 还没取，进房时调 [XhmRepo.getStreamUrl] 补 */
    var streamUrl: String = "",
    /** 是否已开播（列表接口给的在线状态，可能不可靠，仅作排序参考） */
    val liveIn: Boolean = true,
    /**
     * 主播用户 id（列表项的 `id` 字段）。**与 [id]（curroomnum 房间号）不是一回事**：
     * 关注/取关接口 `/OpenAPI/v1/user/follow|unfollow` 的 body 是 `{"uid": <主播用户id>}`，
     * 拿房间号去关注会打错人 —— 2026-10-31 实测确认。
     */
    val anchorId: Long = 0
)

/** 首页分类（热门/新人/颜值/歌舞…）。来自 `/home` 聚合接口里的分类表 */
data class XhmCategory(val name: String, val count: Int = 0)

/** 主播信息：直播间详情 / 关注页 / 主播卡共用 */
data class XhmAnchor(
    val roomId: Long,
    val name: String,
    val avatar: String = "",
    val fans: Int = 0,
    val followCount: Int = 0,
    val intro: String = "",
    val tags: List<String> = emptyList()
)

/** 直播间内一条消息（聊天 / 礼物 / 系统） */
data class XhmMsg(
    val id: Long,
    val nick: String,
    val text: String,
    val kind: String,        // chat / gift / sys / enter
    val ts: Long
)

/** 首页聚合数据：分类 + 房间 */
data class XhmHomeData(
    val categories: List<XhmCategory>,
    val rooms: List<XhmRoom>
)

/** 整个小红帽数据源的解析结果 */
data class XhmSourceData(val data: XhmHomeData)

/** 关注项（本地收藏，与天美 [com.tm.live.data.Follow] 平行） */
data class XhmFollow(
    val id: Long,
    val name: String,
    val cat: String,
    val ts: Long,
    /** 主播用户 id（接口关注/取关用），老数据缺省 0 = 退化用 id */
    val anchorId: Long = 0
)

// ----------------------------------------------------------------- 解析工具

/**
 * 把任意 JSON 值安全地转成 JSONObject（值是字符串化的 JSON 时也能解）。
 * 小红帽不少接口把对象塞在字符串字段里，和天美那条隧道协议一样。
 */
internal fun asObject(v: Any?): JSONObject? = when (v) {
    is JSONObject -> v
    is String -> runCatching { JSONObject(v) }.getOrNull()
    else -> null
}

internal fun asArray(v: Any?): JSONArray? = when (v) {
    is JSONArray -> v
    is String -> runCatching { JSONArray(v) }.getOrNull()
    else -> null
}

/** 取一个房间封面/头像的完整 URL。小红帽的图床多半是相对路径或加密形态，这里只做基本补全 */
internal fun fullImageUrl(base: String, path: String): String {
    val p = path.trim()
    if (p.isEmpty()) return ""
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    val b = base.trim().trimEnd('/')
    if (b.isEmpty()) return ""
    return b + if (p.startsWith("/")) p else "/$p"
}
