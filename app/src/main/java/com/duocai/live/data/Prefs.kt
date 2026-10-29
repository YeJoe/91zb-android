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

    // ---- 播放 ----
    var rtmpDomain: String
        get() = sp.getString(K_RTMP_DOMAIN, Defaults.RTMP_DOMAIN) ?: Defaults.RTMP_DOMAIN
        set(v) = sp.edit().putString(K_RTMP_DOMAIN, v.trim()).apply()

    var hlsGateway: String
        get() = sp.getString(K_HLS_GATEWAY, "") ?: ""
        set(v) = sp.edit().putString(K_HLS_GATEWAY, v.trim()).apply()

    /** 0 适应 / 1 裁剪 / 2 拉伸 */
    var scaleMode: Int
        get() = sp.getInt(K_SCALE_MODE, 1)
        set(v) = sp.edit().putInt(K_SCALE_MODE, v).apply()

    /** 播放缓冲目标，毫秒 */
    var bufferMs: Int
        get() = sp.getInt(K_BUFFER_MS, 1200)
        set(v) = sp.edit().putInt(K_BUFFER_MS, v).apply()

    var autoPlay: Boolean
        get() = sp.getBoolean(K_AUTOPLAY, true)
        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()

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

    // ---- 关注（JSON 数组，明文即可：里面只有房间号和主播名） ----
    var followJson: String
        get() = sp.getString(K_FOLLOW, "") ?: ""
        set(v) = sp.edit().putString(K_FOLLOW, v).apply()

    // ---- 首次引导 ----
    var sawChatSwipeHint: Boolean
        get() = sp.getBoolean(K_SAW_SWIPE, false)
        set(v) = sp.edit().putBoolean(K_SAW_SWIPE, v).apply()

    private companion object {
        const val K_ROOMS_URL = "rooms_url"
        const val K_RTMP_DOMAIN = "rtmp_domain"
        const val K_HLS_GATEWAY = "hls_gateway"
        const val K_SCALE_MODE = "scale_mode"
        const val K_BUFFER_MS = "buffer_ms"
        const val K_AUTOPLAY = "autoplay"
        const val K_DM_WS = "dm_ws"
        const val K_DM_TOKEN = "dm_token"
        const val K_CHAT_ONLY = "chat_only"
        const val K_THEME = "theme"
        const val K_SHOW_AD = "show_ad"
        const val K_FOLLOW = "follow_json"
        const val K_SAW_SWIPE = "saw_swipe_hint"
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
