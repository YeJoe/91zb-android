package com.tm.live.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tm.live.BuildConfig
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

    /**
     * 隧道 token：32 位 hex，来自 quick-login，隧道 URL 的 `?token=`。
     *
     * 2026-10-31 起**默认值来自构建注入的真号**（`BuildConfig.TM_APP_TOKEN`，
     * 由 `local.properties` 或 CI Secrets 提供，见 `app/build.gradle.kts`）。
     * 它同时是弹幕 `/tunnel/open?token=` 与 `/websock/join` 的身份凭证 ——
     * 空着就是 `403 bad token, please login!`，一条弹幕都进不来。
     *
     * 设置页粘一份别的会覆盖它（换号用）；清空即回落到注入值。
     */
    var appToken: String
        get() = Secure.get(sp, K_APP_TOKEN).ifBlank { Defaults.APP_TOKEN }
        set(v) = Secure.put(sp, K_APP_TOKEN, v.trim())

    /**
     * authorization 头的 JWE（源 App prefs 里 `MAINUSER_DATA.token` 原样）。
     *
     * 全仓只有 `/user_follow/my_follow`（服务端关注）和弹幕 join 两处需要它，
     * 但缺了就是 403「用户不存在或超时」，关注分类永远空着、弹幕也连不上。
     *
     * 默认值优先取**构建注入**的真号（`BuildConfig.TM_JWE`）；没有注入时回落到
     * 源码里那串临时号的 JWE，保证开源版本首装也不是一片空白。
     * 设置页留空 = 用默认的；粘贴别的 = 覆盖它（换号时用）。
     */
    var jwe: String
        get() = Secure.get(sp, K_JWE).ifBlank { Defaults.JWE }
        set(v) = Secure.put(sp, K_JWE, v.trim())

    /**
     * 渠道 id（`channel_id`）。
     *
     * `/relationship/add` / `/relationship/remove` 的必填参数之一
     * （pp+0x59d10 命名参数表：`channelId` / `followeeId` / `userId`）。
     * 默认 `ecy` —— 与内置 JWE 同渠道，换号时跟着改。
     */
    var channelId: String
        get() = sp.getString(K_CHANNEL_ID, Defaults.CHANNEL_ID) ?: Defaults.CHANNEL_ID
        set(v) = sp.edit().putString(K_CHANNEL_ID, v.trim()).apply()

    /**
     * 当前登录用户 id（源 App `MAINUSER_DATA.id`）。
     *
     * 关注写接口要 `userId`。默认值跟着注入凭证走（真号 1422303235），
     * 传 0 时多数服务端会按 token 推，但为了和 App 请求体完全一致，能从 prefs 读到就填上。
     */
    var myUserId: Long
        get() = sp.getLong(K_MY_USER_ID, Defaults.USER_ID.toLongOrNull() ?: 0L)
        set(v) = sp.edit().putLong(K_MY_USER_ID, v).apply()

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
    /**
     * 自动刷新间隔（秒）。**默认 0 = 不自动刷新**。
     *
     * 之前默认 20s：一轮要打 8 个分片、每片 250 个 id，等于每隔 20 秒就把整张
     * 列表重拉一遍，既费流量也和"用户其实只想按需刷新"对不上。改成默认关，
     * 想定时刷再在设置里挑（最短 1 分钟，最长可自定义到小时级）。
     */
    var refreshSec: Int
        get() = sp.getInt(K_REFRESH_SEC, 0)
        set(v) = sp.edit().putInt(K_REFRESH_SEC, v).apply()

    /**
     * 允不允许去"借"**源 App 后台跑着的隧道**（扫 /proc/net/tcp 找它的 listener）。
     *
     * 默认关。内置隧道（[com.tm.live.tunnel.Tunnel]）通了之后完全不需要它；
     * 留着这个后门只是为了 appKey 失效时还能临时顶一下，所以做成显式开关，
     * 免得 App 一启动就去蹭别的进程的端口，行为不可预期。
     */
    var sourceTunnel: Boolean
        get() = sp.getBoolean(K_SOURCE_TUNNEL, false)
        set(v) = sp.edit().putBoolean(K_SOURCE_TUNNEL, v).apply()

    /**
     * 封面图床的 HTTP 代理，`host:port`，空 = 直连。
     *
     * 为什么需要：图床是 CloudFront（`drdue73ptt7o.cloudfront.net`），
     * 国内网络对它是整段不通的 —— 实测设备上 TCP 能连上、发请求却零响应，
     * 同一个 URL 在开了代理的电脑上 200。所以给一个"图片走代理"的口子，
     * 有代理（Clash / v2ray 之类）的场景填上就能出图。
     */
    var imgProxy: String
        get() = sp.getString(K_IMG_PROXY, "") ?: ""
        set(v) = sp.edit().putString(K_IMG_PROXY, v.trim()).apply()

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
    /*
     * 这里原本有个 `showAd`（「显示推广位」开关）。
     *
     * 已经删掉，而且不再加回来：这类房间是博彩/棋牌导流，在数据入口就被 [NonHuman]
     * 硬过滤掉了，开关打开也只会是空列表 —— 留着只会让人以为"点了没反应"。
     */

    /**
     * 「附近」页选中的城市（`city_show` 原文，如「靜安區」）。
     * 留空 = 自动取当前列表里房间最多的那个城市。
     */
    var nearbyCity: String
        get() = sp.getString(K_NEARBY_CITY, "") ?: ""
        set(v) = sp.edit().putString(K_NEARBY_CITY, v.trim()).apply()

    /**
     * 首页瀑布流列数：2 = 封面大、看得清；3 = 紧凑，一屏多出一倍的房间。
     *
     * 默认 2 —— 直播列表首先得看清封面/主播，3 列时封面只剩一百多 dp，
     * 人像和房间名都挤成一团。想要一屏多扫几间可以点列表右上角切到 3 列。
     * 只允许 2/3：4 列时封面宽度不到 80dp，主播名和人气全被挤掉，没有意义。
     */
    var gridCols: Int
        get() = sp.getInt(K_GRID_COLS, 2).coerceIn(2, 3)
        set(v) = sp.edit().putInt(K_GRID_COLS, v.coerceIn(2, 3)).apply()

    // ---- 关注（JSON 数组，明文即可：里面只有房间号和主播名） ----
    var followJson: String
        get() = sp.getString(K_FOLLOW, "") ?: ""
        set(v) = sp.edit().putString(K_FOLLOW, v).apply()

    // ---- 小红帽（并列平台）配置 ----
    /**
     * 小红帽接口的会话 token（JWT）。来自小红帽 App 导出的会话：
     * 抓包里的 `jwt_token` 查询参数 / `Authorization: Bearer` 头。
     * 空着 = 小红帽走 demo 样例数据（UI 可玩，但没有真直播）。
     */
    var xhmToken: String
        get() = Secure.get(sp, K_XHM_TOKEN)
        set(v) = Secure.put(sp, K_XHM_TOKEN, v.trim())

    /** 小红帽当前登录用户 id（取流/关注接口要） */
    var xhmUserId: String
        get() = sp.getString(K_XHM_USER_ID, "") ?: ""
        set(v) = sp.edit().putString(K_XHM_USER_ID, v.trim()).apply()

    /** 小红帽 API 基址（默认 api.xt2.club，https） */
    var xhmBaseUrl: String
        get() = sp.getString(K_XHM_BASE, Defaults.XHM_BASE) ?: Defaults.XHM_BASE
        set(v) = sp.edit().putString(K_XHM_BASE, v.trim()).apply()

    /** 小红帽图床基址（封面/头像） */
    var xhmImgBase: String
        get() = sp.getString(K_XHM_IMG, "") ?: ""
        set(v) = sp.edit().putString(K_XHM_IMG, v.trim()).apply()

    /**
     * 小红帽 demo 模式：true = 用内置样例数据，UI 开箱即用。
     * 填好 token 后建议关掉，走真实接口。
     */
    var xhmDemo: Boolean
        get() = sp.getBoolean(K_XHM_DEMO, true)
        set(v) = sp.edit().putBoolean(K_XHM_DEMO, v).apply()

    /**
     * 小红帽本地关注（JSON 数组）。小红帽的写接口（关注主播）同样需要会话，
     * demo/无 token 阶段先落本机 —— 和天美「先落本机、再试服务端」同一策略。
     */
    var xhmFollowJson: String
        get() = sp.getString(K_XHM_FOLLOW, "") ?: ""
        set(v) = sp.edit().putString(K_XHM_FOLLOW, v).apply()

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
        const val K_CHANNEL_ID = "channel_id"
        const val K_MY_USER_ID = "my_user_id"
        const val K_IMG_BASE = "img_base"
        const val K_TUNNEL_PORT = "tunnel_port"
        const val K_REFRESH_SEC = "refresh_sec"
        const val K_RTMP_DOMAIN = "rtmp_domain"
        const val K_HLS_GATEWAY = "hls_gateway"
        const val K_STREAM_MODE = "stream_mode"
        const val K_SCALE_MODE = "scale_mode"
        const val K_BUFFER_MS = "buffer_ms"
        const val K_AUTOPLAY = "autoplay"
        const val K_DM_WS = "dm_ws"
        const val K_DM_TOKEN = "dm_token"
        const val K_CHAT_ONLY = "chat_only"
        const val K_THEME = "theme"
        const val K_NEARBY_CITY = "nearby_city"
        const val K_GRID_COLS = "grid_cols"
        const val K_FOLLOW = "follow_json"
        const val K_XHM_TOKEN = "xhm_token"
        const val K_XHM_USER_ID = "xhm_user_id"
        const val K_XHM_BASE = "xhm_base"
        const val K_XHM_IMG = "xhm_img"
        const val K_XHM_DEMO = "xhm_demo"
        const val K_XHM_FOLLOW = "xhm_follow_json"
        const val K_KIWI_KEY = "kiwi_app_key"
        const val K_KIWI_AUTO = "kiwi_auto"
        const val K_SOURCE_TUNNEL = "source_tunnel"
        const val K_IMG_PROXY = "img_proxy"
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
         * 但那个前缀下回来的是 `<mode iv="…">3</mode>` 加密流，解码必失败。
         * 同一张图的明文原图在 `/static/upload/…`（见 ImageUrls 注释），
         * 所以默认直接指到明文镜像；老的 `/images/<宽>` 写法也会被 ImageUrls.plain 自动纠正。
         */
        const val IMG_BASE = "https://drdue73ptt7o.cloudfront.net/static/upload"

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
         * 2026-10-29 从源 App 运行内存（Dalvik 堆，`.../initEx` 方法名相邻的常量区）
         * 抓到的真值：base64 的 1072 字节密文，`Kiwi.Init` 实测返回 0。
         * 若它失效（SDK 初始化返回非 0），设置页可以直接改，不用重新打包。
         */
        /**
         * 隧道会话 token（32 位 hex）的默认值，来源：**构建期注入**的真号。
         *
         * 它就是弹幕链路的身份：`/tunnel/open?token=` 与 `/websock/join` 都读这一串，
         * 缺了只会得到 `403 {"msg":"bad token, please login!"}`。
         * 构建时没注入（开源 clone / 没配 Secrets）则为空串 —— 看直播不受影响，
         * 关注与弹幕需要用户在设置页自己粘一份。
         */
        val APP_TOKEN: String = BuildConfig.TM_APP_TOKEN

        /**
         * JWE（`alg=dir` / `enc=A128CBC-HS256`，330 字符）。
         *
         * 取值优先级：构建注入的真号 → 代码里的开源兜底（[FALLBACK_JWE]，临时号）。
         * 设置页粘一份会盖过两者；清空输入框即回到这里。
         *
         * 失效表现：`/user_follow/my_follow` 返回 401/403 或空数组、弹幕 join 403。
         */
        val JWE: String = BuildConfig.TM_JWE.ifBlank { FALLBACK_JWE }

        /**
         * 开源兜底凭证：临时号 **423344474**（渠道 `ecy`）的 JWE。
         *
         * 只在构建时没有注入 `TM_JWE` 时生效 —— 这样公开仓库的代码 clone 下来
         * 也能直接编译、装上有数据，不会一片空白。真号凭证请走
         * `local.properties` / CI Secrets（见 `app/build.gradle.kts` 顶部），
         * **不要**改成这里。
         */
        private const val FALLBACK_JWE =
            "eyJhbGciOiJkaXIiLCJlbmMiOiJBMTI4Q0JDLUhTMjU2In0..UL92WOqbRRtDc84vjAloUQ.7O00WSXSCjgaWhQgrngpqx-jqcEYimDZr18hcEHj1y8XFgYM6m-fMZ3bCqZZYc5hMWwPy00n3TtulK7ZQNfIhhKiZk1EaSvsyXCP1dXS-Rcm6USUxJ7HyIw-Lklyu1zoW1INXtTFiqiujPElCJCXDvbZne7OxHHE8d7WK4bzokd0zIyMz6UDHXUhxOxixKKO93-n664Qx8urx8wDtc7H6LJ8hDCJ6Ofccf7nDuY3r2s.wMUa4eXemBncmq9Ynwo9DQ"

        /**
         * 渠道 id。真号 1422303235 的 `MAINUSER_DATA.channel_id == "tui1"`，
         * 与注入的 JWE 同渠道。构建期注入了 `TM_CHANNEL_ID` 时会被它覆盖，
         * 这里只是无注入时的兜底默认值。
         */
        const val CHANNEL_ID = "tui1"

        /**
         * 默认登录用户 id（源 App `MAINUSER_DATA.id`），真号 1422303235。
         * 关注写接口要 `userId`；虽然传 0 时服务端多按 token 推，
         * 但为了和源 App 请求体完全一致，默认值填真号。构建期注入 `TM_USER_ID` 会覆盖它。
         */
        const val USER_ID = "1422303235"

        /** 小红帽 API 主域（接口文档里唯一的应用自有后端；scheme 推断 https） */
        const val XHM_BASE = "https://api.xt2.club"

        const val KIWI_APP_KEY =
            "360wjdqlAsjhpkqV7pq3Mzj6mfrZdxM4yt39ASqwjWgc3Eq4gjbRp4/qzeH3mlgzQCGK197OhozJE7wxXR1lHHvsbbfn8e+7YRS4p3rgdAg33j6OkmmntfBvqcmvA67vkSwe8rFbLZF1IZGAy6U76eqCd8mQzlEovcAqLT9xcU7kpAWc3svYbMwXCDTNL6aufyS5IaerIygPzSeeafzt4+C0kjXKYuolKELlFv4ltQMPRqOQTrxK2fW9qvHMQUsjjlklnAarvVimL8pg2ewANRvxuBGoZEN6ePLSvnLL+ffGdgq+D/1citmYauR6sK6BRiMQnUXFGksyIf09AmqI6GRMhHjgaviv8VFz2GFyW4awq7shOJQbsykqhzI3E9gf2UdWSHElt4fVvZByA5JlaZKeAijN7fitPWs6RaahHMdk8z5NiSPRTQnMN97YMZKaJEEuOts9QqG1h5vzlcXjlGSCfVRmGv+zadHzoE4XDtW0Oqbb4/1hYzS4ageeNXswo6jzXgIiYcg8tXy1WvKYdzy1E7USYGG/WRdmBCM0+nDG3lHsiOK2Lcs6QugL2isZc/W//glYh+mMuN9vnalWv/JYGesap2Q2Gr19TxIJoDvBorZXzNKxq1mVBR3M8Bd14cxBgqi1gEx8te37d8Qpb0rW4NkVi7WGevOG3RsA4mrUPc5kQGtJ70rEC4VFzshCLFQnRoBVeJh1APevNiKtW13/ExcFCyp4EovUeUVmujgjAklG0sql2TMcPLLoYVtCshzwD3FIXB4WorckMge1uldb7pTHHjB55jmW4tsgnyQR7Im+7jBVV9rfuoUSoq2wNpEU8W2xi7D8mVi2XZfwTZGsb6e0p4nSxy1+FLX/4/Lu7pVfFK1rzxbq346mJJgJSZkkEzzlCOKzPpU3WF8rwnYcWqdNYLnyzXTNpCQjLcNeqBZU8uQoSxyBANmw0AoslNGRImN7m6H81DtbP8XY28Pa9wIKQBw/u/mAvMxtVE2b8smZlt7CU733X3ikkkRGR5F6kRhGmulQbcuqLHiwI5Y9Tq9kEhU+VCCA5+56lmW+NukiIqYBgL9H3woNHTF9JHw/H0UwqacQzruCZ+ez4zbP2HGXvW/4TlJ5LCFhbiU1o3N7VWgsehj+zxsOQoRq3+XhcJo2V6z2GYYytgTUDT7QTYK64ThDJ0vnUnizTqlPppRn0AU+jwh3YOj/QGhQOM4sKD2CcHfoafcwNMoyb5phHi3xuoGE4uyeQUeRKc0EvJ1SVFlt6OT9FwBlhVMwbYl7Jgs3yty0g6WMxmVDUM8+RYawSFjoKNGRr4ckXCCqpX7+E+/mqAhEW/NZWWw63f4oPlPdOSZTva1NVdIlLbcAuaGzQImU4Fg/a9DRSCF+UcHCivl1p959xoWcLiD9H/eAyKnVFckebrlEpJnT7Q=="
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
