package com.duocai.live.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配置存储。
 *
 * 用 SharedPreferences 而不是 DataStore：这里全是"改一次读很多次"的标量，
 * DataStore 的异步 API 只会让每个设置项多绕一圈 Flow。省一个依赖，也省掉首帧读不到值的问题。
 *
 * 唯一需要加密的是弹幕 WS 的鉴权 Token（可选项）—— 走 Android Keystore AES/GCM，
 * 密钥不出 TEE，落在磁盘上的只有密文。加密失败也不影响使用，直接退化为明文并打标记。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("tm_live", Context.MODE_PRIVATE)

    // ---- 数据源 ----
    var roomsUrl: String
        get() = sp.getString(K_ROOMS_URL, Defaults.ROOMS_URL) ?: Defaults.ROOMS_URL
        set(v) = sp.edit().putString(K_ROOMS_URL, v.trim()).apply()

    /**
     * 实时模式开关：true = 直连隧道端口（推荐），false = 拉远程 JSON（旧行为，作降级）。
     */
    var liveMode: Boolean
        get() = sp.getBoolean(K_LIVE_MODE, true)
        set(v) = sp.edit().putBoolean(K_LIVE_MODE, v).apply()

    /** 隧道 token：32 位 hex，来自 quick-login，隧道 URL 的 ?token= */
    var appToken: String
        get() = Secure.get(sp, K_APP_TOKEN)
        set(v) = Secure.put(sp, K_APP_TOKEN, v.trim())

    /** authorization 头的 JWE（源 App prefs 里 MAINUSER_DATA.token 原样） */
    var jwe: String
        get() = Secure.get(sp, K_JWE)
        set(v) = Secure.put(sp, K_JWE, v.trim())

    /**
     * 封面图床基址（含尺寸档）。
     *
     * 实测：`drdue73ptt7o.cloudfront.net` 上 `/images/256/…` `/480/…` `/640/…` `/1024/…`
     * 都能取到，其中 480 之后返回的都是同一张原图（61365 字节），所以取 480 最省流量。
     * 这个域名是 CloudFront 分发，跟隧道无关，直连即可，不需要任何凭证。
     */
    var imgBase: String
        get() = sp.getString(K_IMG_BASE, Defaults.IMG_BASE) ?: Defaults.IMG_BASE
        set(v) = sp.edit().putString(K_IMG_BASE, v.trim()).apply()

    /** 上次探测成功的隧道端口，0 = 未探测 */
    var tunnelPort: Int
        get() = sp.getInt(K_TUNNEL_PORT, 0)
        set(v) = sp.edit().putInt(K_TUNNEL_PORT, v).apply()

    /** 自动刷新间隔（秒），0 = 关闭 */
    var refreshSec: Int
        get() = sp.getInt(K_REFRESH_SEC, 20)
        set(v) = sp.edit().putInt(K_REFRESH_SEC, v).apply()

    // ---- 播放 ----
    var rtmpDomain: String
        get() = sp.getString(K_RTMP_DOMAIN, Defaults.RTMP_DOMAIN) ?: Defaults.RTMP_DOMAIN
        set(v) = sp.edit().putString(K_RTMP_DOMAIN, v.trim()).apply()

    var hlsGateway: String
        get() = sp.getString(K_HLS_GATEWAY, "") ?: ""
        set(v) = sp.edit().putString(K_HLS_GATEWAY, v.trim()).apply()

    /**
     * 播放通道：0 自动（HTTP-FLV → RTMP）/ 1 只 FLV / 2 只 RTMP。
     * 默认 0 —— 实测 FLV 能播、librtmp 连不上，自动降级最省心。
     */
    var streamMode: Int
        get() = sp.getInt(K_STREAM_MODE, 0)
        set(v) = sp.edit().putInt(K_STREAM_MODE, v).apply()

    /**
     * 0 适应（FIT）/ 2 拉伸（FILL）。
     *
     * 旧的 1=裁剪 已废弃：固定裁切会切掉画面边缘，改成播放页双指自由缩放后不再需要。
     * 老用户存着 1 的读出来按 0 处理，不用弹迁移。
     */
    var scaleMode: Int
        get() = sp.getInt(K_SCALE_MODE, 0).let { if (it == 1) 0 else it }
        set(v) = sp.edit().putInt(K_SCALE_MODE, v).apply()

    /** 播放缓冲目标，毫秒 */
    var bufferMs: Int
        get() = sp.getInt(K_BUFFER_MS, 1200)
        set(v) = sp.edit().putInt(K_BUFFER_MS, v).apply()

    var autoPlay: Boolean
        get() = sp.getBoolean(K_AUTOPLAY, true)
        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()

    /**
     * 播放页按 Home 时自动缩进画中画。默认开 —— 这是视频类 App 的通行行为；
     * 嫌它"动不动就浮窗"可以在设置里关掉，此时只能点顶栏「浮窗」手动进。
     */
    var autoPip: Boolean
        get() = sp.getBoolean(K_AUTO_PIP, true)
        set(v) = sp.edit().putBoolean(K_AUTO_PIP, v).apply()

    // ---- 弹幕 ----
    var dmWs: String
        get() = sp.getString(K_DM_WS, "") ?: ""
        set(v) = sp.edit().putString(K_DM_WS, v.trim()).apply()

    var dmToken: String
        get() = Secure.get(sp, K_DM_TOKEN)
        set(v) = Secure.put(sp, K_DM_TOKEN, v.trim())

    var chatOnly: Boolean
        get() = sp.getBoolean(K_CHAT_ONLY, true)
        set(v) = sp.edit().putBoolean(K_CHAT_ONLY, v).apply()

    // ---- 外观 ----
    /** 0 跟随系统 / 1 深色 / 2 浅色 */
    var themeMode: Int
        get() = sp.getInt(K_THEME, 0)
        set(v) = sp.edit().putInt(K_THEME, v).apply()

    // ---- 列表 ----
    var showAd: Boolean
        get() = sp.getBoolean(K_SHOW_AD, false)
        set(v) = sp.edit().putBoolean(K_SHOW_AD, v).apply()

    /**
     * 「附近」页选中的城市（`city_show` 原文，如「靜安區」）。
     * 留空 = 自动取当前列表里房间最多的那个城市。
     */
    var nearbyCity: String
        get() = sp.getString(K_NEARBY_CITY, "") ?: ""
        set(v) = sp.edit().putString(K_NEARBY_CITY, v.trim()).apply()

    // ---- 关注（JSON 数组，明文即可：里面只有房间号和主播名） ----
    var followJson: String
        get() = sp.getString(K_FOLLOW, "") ?: ""
        set(v) = sp.edit().putString(K_FOLLOW, v).apply()

    // ---- 自研隧道（内置云层 SDK，不再依赖源 App 常驻）----
    /**
     * 云层 SDK 的 appKey。SDK 的 `Init(String appkey)` 只认这一个参数，
     * 没有 token 一类的第二凭证。留空 = 不启用内置隧道，退回"借源 App 端口"。
     */
    var kiwiAppKey: String
        get() = sp.getString(K_KIWI_KEY, Defaults.KIWI_APP_KEY) ?: Defaults.KIWI_APP_KEY
        set(v) = sp.edit().putString(K_KIWI_KEY, v.trim()).apply()

    /** 启动时自动拉起内置隧道 */
    var kiwiAuto: Boolean
        get() = sp.getBoolean(K_KIWI_AUTO, true)
        set(v) = sp.edit().putBoolean(K_KIWI_AUTO, v).apply()

    // ---- 首次引导 ----
    var sawChatSwipeHint: Boolean
        get() = sp.getBoolean(K_SAW_SWIPE, false)
        set(v) = sp.edit().putBoolean(K_SAW_SWIPE, v).apply()

    /** 播放页"上下滑动切换直播间"的提示，只弹一次 */
    var sawPlayerSwipeHint: Boolean
        get() = sp.getBoolean(K_SAW_PLAYER_SWIPE, false)
        set(v) = sp.edit().putBoolean(K_SAW_PLAYER_SWIPE, v).apply()

    private companion object {
        const val K_ROOMS_URL = "rooms_url"
        const val K_LIVE_MODE = "live_mode"
        const val K_APP_TOKEN = "app_token"
        const val K_JWE = "jwe"
        const val K_IMG_BASE = "img_base"
        const val K_TUNNEL_PORT = "tunnel_port"
        const val K_REFRESH_SEC = "refresh_sec"
        const val K_RTMP_DOMAIN = "rtmp_domain"
        const val K_HLS_GATEWAY = "hls_gateway"
        const val K_STREAM_MODE = "stream_mode"
        const val K_SCALE_MODE = "scale_mode"
        const val K_BUFFER_MS = "buffer_ms"
        const val K_AUTOPLAY = "autoplay"
        const val K_AUTO_PIP = "auto_pip"
        const val K_DM_WS = "dm_ws"
        const val K_DM_TOKEN = "dm_token"
        const val K_CHAT_ONLY = "chat_only"
        const val K_THEME = "theme"
        const val K_SHOW_AD = "show_ad"
        const val K_NEARBY_CITY = "nearby_city"
        const val K_FOLLOW = "follow_json"
        const val K_KIWI_KEY = "kiwi_app_key"
        const val K_KIWI_AUTO = "kiwi_auto"
        const val K_SAW_SWIPE = "saw_swipe_hint"
        const val K_SAW_PLAYER_SWIPE = "saw_player_swipe_hint"
    }

    object Defaults {
        /**
         * 默认数据源走 jsDelivr 镜像而不是 raw.githubusercontent.com：
         * 后者在国内经常被墙，App 首启就会"取不到列表"。jsDelivr 会缓存（几小时），
         * 刷新数据后想立刻看到，可以在设置页换成 raw 地址。
         */
        const val ROOMS_URL =
            "https://cdn.jsdelivr.net/gh/YeJoe/91zb-android@duocai/app-data/rooms.json"

        /** GitHub 原始地址，作备用 */
        const val ROOMS_URL_RAW =
            "https://raw.githubusercontent.com/YeJoe/91zb-android/duocai/app-data/rooms.json"

        /**
         * 抓包时的 RTMP 域名。它不是凭证（拉流不需要任何 token），
         * 但会随服务端调度变化，所以设置页可改。
         */
        const val RTMP_DOMAIN = "1dtluv36amaiuckq.7kv5kqq7k77g.hebeisaixin.com"

        /**
         * 封面图床。抓包实测：源 App 拉头像是 `GET drdue73ptt7o.cloudfront.net/images/256/…`，
         * 与隧道、token 都无关，公网直连 200。
         */
        const val IMG_BASE = "https://drdue73ptt7o.cloudfront.net/images/480"

        /**
         * 云层 SDK 的 appKey。
         *
         * 来源：blutter 从 `libapp.so` 的对象池里 dump 出来的 `main.dart` 常量区，
         * 与 `"kiwi_"` 前缀、`http://kiwi_live/tunnel/a033b072?z=1&nobase64=1` 紧挨着：
         *
         * ```
         * [pp+0x2830] String: "kiwi_"
         * [pp+0x2838] String: "dNLmP4x5bcvPKZess6gqv6MzS+2bg…"      // 长 Base64
         * [pp+0x2840] String: "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4"     // ← 32 位，就是它
         * [pp+0x2848] String: "http://kiwi_live/tunnel/a033b072?z=1&nobase64=1"
         * [pp+0x2850] String: "OIWKj3hGjOKA6pU1lMcZvC0…"             // 长 Base64
         * ```
         *
         * 若它失效（SDK 初始化返回非 0），设置页可以直接改，不用重新打包。
         */
        const val KIWI_APP_KEY = "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4"
    }
}

/** Keystore AES/GCM 小工具，失败就退化为明文（宁可丢一点安全性，也不能丢功能） */
private object Secure {
    private const val ALIAS = "tm_live_secret"
    private const val PREFIX_ENC = "enc:"
    private const val PREFIX_RAW = "raw:"

    fun put(sp: SharedPreferences, key: String, value: String) {
        if (value.isEmpty()) {
            sp.edit().remove(key).apply()
            return
        }
        val stored = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val all = iv + body
            PREFIX_ENC + Base64.encodeToString(all, Base64.NO_WRAP)
        }.getOrElse { PREFIX_RAW + value }
        sp.edit().putString(key, stored).apply()
    }

    fun get(sp: SharedPreferences, key: String): String {
        val stored = sp.getString(key, "") ?: return ""
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PREFIX_RAW)) return stored.removePrefix(PREFIX_RAW)
        if (!stored.startsWith(PREFIX_ENC)) return stored
        return runCatching {
            val all = Base64.decode(stored.removePrefix(PREFIX_ENC), Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val body = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }
}
