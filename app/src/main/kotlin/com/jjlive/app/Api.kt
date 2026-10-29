package com.jjlive.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 91直播接口常量。
 *
 * 以网页版（最新）为准：terminal=3 / channel=12 / version=436。
 */
object Cfg {
    const val HOST = "https://utp.opuxrcm.com"
    const val SPARE_HOST = "https://ytt.jvjzmc.com"
    /** 弹幕（IM）长连接，和接口域名不是同一个子域，所以单独配置。 */
    const val IM_WS = "wss://yzj.opuxrcm.com/live"
    /** 首次安装用的默认令牌；失效后在「设置」里换一个即可，不用重装。 */
    const val DEFAULT_TOKEN =
        "24e9ab5c9f37247821f4edf5061946e5429de983e86d43b374533b997073ddf5fb69457b759d4da294a290addfa2f6fe"
    const val SALT = "oknzoiwe@23"
    const val AK = "jyzb"
    const val TERMINAL = "3"
    const val CHANNEL = "12"
    const val VERSION = "436"
    const val LANG = "zh-CN"

    /** 图片域名。实测返回的 JPEG 前有多余 1 字节，需剥离后再解码。 */
    const val IMG_HOST = "https://rgf.rucqnij.xyz"
}

// ─────────────────────────── 加密三件套 ───────────────────────────

/** Dart: codeUnits.map((c) => (c ^ 202030) & 0xFFFF) —— 自逆。 */
fun xorCode(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(((c.code xor 0x152E) and 0xFFFF).toChar())
    return sb.toString()
}

fun md5(s: String): String {
    val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

/** base64 -> 逐字符小写十六进制 -> 前置随机前缀（服务端忽略，长度须为奇数）。 */
fun encodePath(path: String, prefix: String): String {
    val b64 = Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val sb = StringBuilder()
    for (c in b64) sb.append("%x".format(c.code))
    return "/$prefix$sb"
}

fun randomHex(n: Int): String {
    val cs = "0123456789abcdef"
    return (1..n).map { cs.random() }.joinToString("")
}

/**
 * x-auth-sign = md5(path + "?" + 排序后的 k=v 串 + salt)
 * 参与键：ak / authorization / channel / terminal / version / x-auth-timestamp + 全部业务参数
 * 不含 content-language、deviceuid。
 */
fun makeSign(path: String, token: String, ts: String, params: Map<String, String>): String {
    val d = HashMap<String, String>()
    d["ak"] = Cfg.AK
    d["authorization"] = token
    d["channel"] = Cfg.CHANNEL
    d["terminal"] = Cfg.TERMINAL
    d["version"] = Cfg.VERSION
    d["x-auth-timestamp"] = ts
    d.putAll(params)
    val src = path + "?" + d.keys.sorted().joinToString("&") { "$it=${d[it]}" }
    return md5(src + Cfg.SALT)
}

// ─────────────────────────── 令牌与设备号 ───────────────────────────

/** 补全协议头、去掉末尾斜杠；填不出合法地址就返回 null（调用方回落默认）。 */
fun normalizeHost(raw: String?): String? {
    var s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    s = s.trimEnd('/')
    return if (s.length > "https://".length + 2) s else null
}

class TokenStore(context: Context) {
    private val sp = context.getSharedPreferences("jjlive", Context.MODE_PRIVATE)

    /** 接口域名，默认 Cfg.HOST；用户可在「设置」里改成备用域名。 */
    var host: String
        get() = normalizeHost(sp.getString("host", null)) ?: Cfg.HOST
        set(v) = sp.edit().putString("host", normalizeHost(v) ?: "").apply()

    /** 弹幕 WS，留空回落 Cfg.IM_WS。 */
    var imHost: String
        get() = sp.getString("imHost", null)?.trim()?.takeIf { it.isNotBlank() } ?: Cfg.IM_WS
        set(v) = sp.edit().putString("imHost", v.trim()).apply()

    /** 令牌永远有值（默认令牌兜底），失效后到「设置」里换。 */
    var token: String
        get() = sp.getString("token", null)?.trim()?.takeIf { it.isNotBlank() } ?: Cfg.DEFAULT_TOKEN
        set(v) = sp.edit().putString("token", v.trim()).apply()

    val deviceUid: String
        get() = sp.getString("uid", null) ?: randomHex(32).also {
            sp.edit().putString("uid", it).apply()
        }

    /** 全部改成内置默认值。 */
    fun resetAll() {
        sp.edit().remove("host").remove("imHost").remove("token").apply()
    }
}

// ─────────────────────────── 网络 ───────────────────────────

class Api(private val store: TokenStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun call(path: String, params: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val token = store.token
            val ts = System.currentTimeMillis().toString()
            val sign = makeSign(path, token, ts, params)
            val url = store.host + encodePath(path, randomHex(3))

            // 紧凑 JSON，与 Python separators=(",",":") 一致。
            // 整数值按数字输出，长 ID（超出 Int 范围）按字符串输出，
            // 与原始抓包行为保持一致。
            val bodyJson = if (params.isEmpty()) "{}"
            else params.entries.joinToString(",", "{", "}") { (k, v) ->
                val n = v.toIntOrNull()
                "\"$k\":" + (n?.toString() ?: JSONObject.quote(v))
            }
            val bodyBytes = xorCode(bodyJson).toByteArray(Charsets.UTF_8)

            val req = Request.Builder()
                .url(url)
                .header("ak", Cfg.AK)
                .header("authorization", token)
                .header("channel", Cfg.CHANNEL)
                .header("terminal", Cfg.TERMINAL)
                .header("version", Cfg.VERSION)
                .header("deviceuid", store.deviceUid)
                .header("content-language", Cfg.LANG)
                .header("x-auth-timestamp", ts)
                .header("x-auth-sign", sign)
                .header("content-type", "application/json")
                .post(bodyBytes.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val plain = if (raw.isNotEmpty() && raw[0].code > 0x1400) xorCode(raw) else raw
                    if (plain.isEmpty()) return@withContext JSONObject()
                    runCatching { JSONObject(plain) }.getOrNull()
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("__error", "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

    /**
     * 校验「域名 + 令牌」是否可用（用最轻的接口，不落任何副作用）。
     * 设置页点「测试」时传入输入框里的值，不必先保存。
     */
    suspend fun checkToken(host: String, token: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val base = normalizeHost(host) ?: return@withContext false to "域名格式不对"
            if (token.isBlank()) return@withContext false to "令牌为空"
            val ts = System.currentTimeMillis().toString()
            val sign = makeSign("/param/list", token, ts, emptyMap())
            val url = base + encodePath("/param/list", randomHex(3))
            val req = Request.Builder()
                .url(url)
                .header("ak", Cfg.AK)
                .header("authorization", token)
                .header("channel", Cfg.CHANNEL)
                .header("terminal", Cfg.TERMINAL)
                .header("version", Cfg.VERSION)
                .header("deviceuid", store.deviceUid)
                .header("content-language", Cfg.LANG)
                .header("x-auth-timestamp", ts)
                .header("x-auth-sign", sign)
                .header("content-type", "application/json")
                .post(xorCode("{}").toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val plain = if (raw.isNotEmpty() && raw[0].code > 0x1400) xorCode(raw) else raw
                    val j = runCatching { JSONObject(plain) }.getOrNull()
                    val ok = j != null && j.optInt("code", -1) == 0
                    if (ok) true to "连接正常"
                    else false to (j?.optString("msg").orEmpty().ifEmpty { "HTTP ${resp.code}" })
                }
            } catch (e: Exception) {
                false to "${e.javaClass.simpleName}: ${e.message}"
            }
        }
}

// ─────────────────────────── 模型与解析 ───────────────────────────

data class Category(val id: Int, val name: String, val fixedType: Int)

data class Room(
    val roomId: String,
    val userId: String,
    val userName: String,
    val roomName: String,
    val hot: Int,
    val cover: String,
    val liveType: Int,
    val payType: Int,
    val moneyFen: Int,
    val oneToOneState: Int,
    val url0: String,
    val url360: String,
    val url720: String,
    val cats: List<String>,
    val anchorType: Int = 2,
    val gameRoom: Int = 0
) {
    val isPay: Boolean get() = moneyFen > 0
    val performing: Boolean get() = oneToOneState == 2
    val hasStream: Boolean get() = url0.isNotEmpty() || url360.isNotEmpty() || url720.isNotEmpty()

    /**
     * 广告 / 赌博直播间。
     *
     * 实测样本里 anchorType=1 的房间全是「凤凰快三 / 百家乐 / pc28 / 龙虎」这类博彩推广房，
     * 正常主播都是 anchorType=2；再加一层名称关键字兜底（有些广告房仍用 2）。
     */
    val isAd: Boolean
        get() = anchorType == 1 || JUNK_WORDS.any { hit(it, userName, roomName) }

    /** 游戏直播间：接口的 gameRoom 标记 + 名称关键字。 */
    val isGame: Boolean
        get() = gameRoom == 1 || GAME_WORDS.any { hit(it, userName, roomName) }

    private fun hit(k: String, vararg fields: String): Boolean =
        fields.any { it.contains(k, ignoreCase = true) }

    /** 取优先级：原画 > 720P > 360P */
    fun bestUrl(): String =
        url0.ifEmpty { url720.ifEmpty { url360 } }

    fun coverUrl(): String = if (cover.isEmpty()) "" else Cfg.IMG_HOST + cover

    companion object {
        /** 广告、博彩、引流话术 */
        private val JUNK_WORDS = listOf(
            "快三", "百家乐", "龙虎", "时时彩", "彩票", "棋牌", "包赢", "回血",
            "加微", "加v", "加V", "微信", "飞机", "电报", "telegram", "扣扣", "企鹅群",
            "兼职", "日赚", "日入", "月入", "返利", "招商", "代理", "推广", "引流",
            "官方推荐", "下载app", "下载APP", "注册送", "首充", "福利群", "上车"
        )

        /** 游戏直播（这类房基本没有人看，和需求里说的「游戏直播间」一起过滤掉） */
        private val GAME_WORDS = listOf(
            "王者荣耀", "英雄联盟", "和平精英", "吃鸡", "开黑", "陪玩", "代练",
            "游戏直播", "蛋仔派对", "穿越火线", "csgo", "CSGO", "刀塔", "DOTA", "永劫无间"
        )
    }
}

private fun JSONObject.str(vararg keys: String): String {
    for (k in keys) {
        val v = optString(k, "")
        if (v.isNotEmpty() && !v.equals("null", true)) return v
    }
    return ""
}

private fun JSONObject.int(vararg keys: String, def: Int = 0): Int {
    for (k in keys) if (has(k)) return optInt(k, def)
    return def
}

object Parser {

    fun categories(js: JSONObject?): List<Category> {
        val arr = js?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ft = o.int("fixedType", "fixedType", def = -1)
            if (ft < 0) continue
            out.add(Category(ft, o.str("clsName").ifEmpty { "类别$ft" }, ft))
        }
        return out
    }

    /** 兼容两套字段名：列表用 pullStreamAddr*，进房用 pullAddress*。 */
    fun room(o: JSONObject, cats: List<String> = emptyList()): Room {
        val mf = o.int("moneyFen", def = 0)
        return Room(
            roomId = o.str("roomId"),
            userId = o.str("userId"),
            userName = o.str("userName", "nickname"),
            roomName = o.str("roomName"),
            hot = o.int("currentHot", def = 0),
            cover = o.str("roomCover"),
            liveType = o.int("liveType", def = 1),
            payType = o.int("payType", def = 0),
            moneyFen = mf,
            oneToOneState = o.int("oneToOneState", "stateOneToOne", def = 1),
            url0 = o.str("pullStreamAddr", "pullAddress"),
            url360 = o.str("pullStreamAddr360", "pullAddress360"),
            url720 = o.str("pullStreamAddr720", "pullAddress720"),
            cats = cats,
            anchorType = o.int("anchorType", def = 2),
            gameRoom = o.int("gameRoom", def = 0)
        )
    }

    fun rooms(js: JSONObject?): List<Room> {
        val out = ArrayList<Room>()
        val data = js?.opt("data")
        val arr: org.json.JSONArray? = when (data) {
            is org.json.JSONArray -> data
            is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("data")
            else -> null
        }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(room(it)) }
            }
        }
        return out
    }
}

// ─────────────────────────── 图片 ───────────────────────────

object Images {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取封面并解码。
     * 注意：图片域名返回的 JPEG 前会多一个非魔术字节（实测 0x31），
     * 直接按标准头判断会失败，所以这里扫描真正的魔术位置再解码。
     */
    fun load(url: String): android.graphics.Bitmap? {
        if (url.isEmpty()) return null
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: return null
                stripAndDecode(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripAndDecode(b: ByteArray): android.graphics.Bitmap? {
        if (b.size < 16) return null
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        val ji = indexOf(b, jpeg)
        val pi = indexOf(b, png)
        val start = when {
            ji >= 0 && (pi < 0 || ji < pi) -> ji
            pi >= 0 -> pi
            else -> 0
        }
        return android.graphics.BitmapFactory.decodeByteArray(b, start, b.size - start)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        val limit = hay.size - needle.size
        outer@ for (i in 0..limit) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
