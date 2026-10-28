package com.jjlive.app

import org.json.JSONObject

/**
 * 弹幕过滤器。
 *
 * 线上 IM 帧的真实类别（用 WebSocket 抓 3 个房间、108 帧统计得出，evt 是帧里的整型事件号）：
 *
 * | evt | 含义                                   | 实测样本                                      | 处理 |
 * |-----|----------------------------------------|-----------------------------------------------|------|
 * | 5   | 正常聊天，正文在 msg 字段               | {"evt":5,"msg":"大哥走了吧","nn":"事了无趣"}   | 保留 |
 * | 1   | 用户进入直播间（有 nn/lv，无正文）      | {"evt":1,"nn":"宋宛_z7yc","lv":2}             | 屏蔽 |
 * | 17  | 下注                                   | {"evt":17,"bet":"2.00","gn":"一分快三"}        | 屏蔽 |
 * | 18  | 中奖/赢得                              | {"evt":18,"winMoney":"39.20"}                  | 屏蔽 |
 * | 19  | 游戏开奖开始                           | {"evt":19,"gn":"一分快三"}                     | 屏蔽 |
 * | 21  | 房间在线人数统计                       | {"evt":21,"ls":1,"us":1}                       | 屏蔽 |
 * | 22  | 无正文（点赞/心跳类）                  | {"evt":22,"lid":"...","mid":"..."}             | 屏蔽 |
 * | 44  | 游戏投注档位                           | {"evt":44,"skmn":"低","skp":200}               | 屏蔽 |
 * | 3/53| 关注、分享等（只有 nn/lv）             | {"evt":53,"nn":"米颜函_sz8l"}                  | 屏蔽 |
 *
 * 结论：**只有带正文的帧才可能是聊天**，其余按事件号直接丢；正文再叠一层关键词，
 * 把「进入直播间 / 赢得 / 下注 / 加微 / 电报」这类营销与博彩话术也挡掉。
 * 这样即使服务端以后给某个事件号补了文案，也不会漏出来。
 */
object ChatFilter {

    /** 明确与聊天无关的事件号。 */
    private val BLOCKED_EVT = setOf(1, 3, 17, 18, 19, 21, 22, 44, 53)

    private val TEXT_KEYS = arrayOf("msg", "txt", "content", "txts")

    /** 正文里出现即丢弃的词：博彩 / 引流 / 进房播报。 */
    private val BAD_WORDS = arrayOf(
        // 进房播报
        "进入直播间", "进入了直播间", "来了直播间", "欢迎来到",
        // 博彩
        "下注", "押注", "投注", "注单", "上分", "下分", "上号", "下分",
        "开奖", "快三", "快３", "pc28", "PC28", "龙虎", "百家乐", "时时彩",
        "六合", "彩票", "菠菜", "包赔", "稳赚", "回血", "上岸", "首存", "派奖",
        "中奖", "赢得", "赢钱", "倍投", "跟单", "带单", "计划群", "导师",
        "充值", "提现", "返水", "彩金", "彩金", "首充", "福利金",
        // 引流
        "加微", "加V", "加v", "微信", "威信", "薇信", "电报", "飞机", "私聊",
        "看主页", "主页有", "看简介", "企鹅", "扣扣", "qq群", "QQ群", "群号",
        "日赚", "兼职", "刷单", "招收", "代理", "推广",
        // 链接
        "http", "www.", "t.me", ".com", ".cn"
    )

    /** 太长的几乎都是刷屏广告。 */
    private const val MAX_LEN = 120

    /**
     * 一帧解密后的文本 → 可展示的聊天消息。
     * @return null 表示这条应当丢弃
     */
    fun parse(raw: String): ChatMsg? {
        val obj = jsonOf(raw)
        if (obj != null) {
            val evt = obj.optInt("evt", -1)
            if (evt in BLOCKED_EVT) return null
            val body = firstText(obj) ?: return null
            val nick = firstString(obj, "nn", "cn", "nickname", "un")
            return build(nick, body, obj.optInt("lv", 0))
        }

        // 极端情况：JSON 被截断，退回正则捞字段（仍然走同一套过滤）
        val nick = Regex("\"(?:nn|cn|nickname)\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
        val body = Regex("\"(?:msg|txt|content)\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
        return build(nick ?: "", body ?: return null, 0)
    }

    private fun build(nickRaw: String, bodyRaw: String, level: Int): ChatMsg? {
        val body = unescape(bodyRaw).trim()
        if (body.isEmpty() || body.length > MAX_LEN) return null

        // 全角/大小写无关地比对
        val probe = body.lowercase()
        for (w in BAD_WORDS) {
            if (probe.contains(w.lowercase())) return null
        }

        val nick = unescape(nickRaw).trim().ifEmpty { "游客" }
        return ChatMsg(nick = nick, body = body, level = level)
    }

    // ─────────────────────── 解析辅助 ───────────────────────

    private fun jsonOf(raw: String): JSONObject? {
        val i = raw.indexOf('{')
        val j = raw.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        return runCatching { JSONObject(raw.substring(i, j + 1)) }.getOrNull()
    }

    private fun firstText(o: JSONObject): String? {
        for (k in TEXT_KEYS) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    /** 处理 JSON 里残留的 \uXXXX、\n 转义。 */
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6; continue }
                    }
                    'n' -> { sb.append(' '); i += 2; continue }
                    't' -> { sb.append(' '); i += 2; continue }
                    '"' -> { sb.append('"'); i += 2; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
