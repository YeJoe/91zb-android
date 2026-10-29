package com.duocai.live.tunnel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duocai.live.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 内置隧道的运行外壳：把 [KiwiSdk] 那套"在 IO 线程同步握手"的调用包成可观测的状态。
 *
 * 这个类的存在意义只有一个 —— 让 UI 和 [com.duocai.live.data.LiveRepo] 都不用直接碰到
 * 反射和线程切换：两者只看 [port] 和 [state]。
 *
 * 状态机：
 *
 * ```
 * 未启动 ──start()──► 握手(IO, 数秒) ──► 就绪(port>0) ──► LiveRepo 走 127.0.0.1:port
 *                        └──失败──► 失败(msg)  ──► LiveRepo 退回扫描本机端口（源 App 模式）
 * ```
 *
 * ## 为什么"拿到端口"还不算成功
 *
 * SDK 会给**每个 vhost 组**各开一个本地 listener（源 App 进程里同时有 17 个 LISTEN 端口，
 * 其中 13 个对应 13 个组）。`ServerToLocal(group)` 只回答"这个组有没有 listener"，
 * 它不回答"这个组是不是挂 `/room` 前缀的那一个"。
 *
 * 实测：随便挑一个组拿到端口去打 `/room/get_list_by_ids`，回的是 openresty 的 404 HTML
 * ——说明**隧道是通的**（请求确实到了远端），只是 Host 路由到了错的 vhost。
 * 所以 [start] 里必须"拿到端口后再真打一次接口"，能回 `{"rooms":…}` 的那个组才算数。
 */
class Tunnel(private val context: Context, private val prefs: Prefs) {

    enum class State { IDLE, STARTING, READY, FAILED }

    var state by mutableStateOf(State.IDLE)
        private set

    /** 给设置页直接显示的一句话 */
    var message by mutableStateOf("未启动")
        private set

    /** SDK 的 Init 返回码（0 = 成功） */
    var initCode by mutableStateOf(Int.MIN_VALUE)
        private set

    /** 就绪后的本地端口，0 = 不可用 */
    var port by mutableStateOf(0)
        private set

    var host by mutableStateOf("")
        private set

    /** 命中 /room 的那个 vhost 组名（`kiwi_go` 之类） */
    var group by mutableStateOf("")
        private set

    /** 最近一次启动的探测明细（每个 group 的返回码），失败时贴给设置页看 */
    var diag by mutableStateOf("")
        private set

    val ready: Boolean get() = state == State.READY && port > 0

    /** 同一时刻只允许一趟 start（用户连点按钮时不能起两条握手） */
    @Volatile private var starting = false

    /**
     * 拉起隧道。多次调用只会有一次真正在跑（[starting] 挡住并发）。
     *
     * @return 是否就绪
     */
    suspend fun start(appKey: String = prefs.kiwiAppKey): Boolean {
        if (ready) return true
        if (starting) return false

        // 架构门禁必须放在最前面：x86 设备上 dlopen 一个 arm64 库，
        // 加固库的 JNI_OnLoad 会在 pending exception 状态下继续跑，直接 abort 整个进程，
        // Kotlin 的 try/catch 完全拦不住。所以宁可提前拒绝。
        if (!KiwiSdk.abiSupported()) {
            fail(KiwiSdk.abiReason())
            return false
        }

        starting = true
        state = State.STARTING
        message = "加载 SDK…"
        return try {
            withContext(Dispatchers.IO) {
                if (!KiwiSdk.ensureLoaded(context)) {
                    fail("SDK 加载失败：${KiwiSdk.lastError}")
                    return@withContext false
                }
                message = "初始化（需要和云端握手，约几秒）…"
                val code = KiwiSdk.init(context, appKey)
                initCode = code
                if (code != 0) {
                    fail("Init 返回 $code（appKey 无效或被风控）")
                    return@withContext false
                }

                // Init 只做完握手（appKey 校验 + 节点下发），本地 listener 要
                // RestartAllServer 才会真正起起来；少了这步 ServerToLocal 必返 -5。
                message = "启动隧道 listener…"
                KiwiSdk.restartAllServer(context)

                // listener 是被 native 线程异步拉起来的，给它们一点时间 bind。
                // 太短会看到"组存在但端口还没开"，太长白等；2s 是实测的起步值，
                // 真正的兜底是下面那三轮重探。
                delay(2000)

                // 一次把所有 vhost 都问一遍：ServerToLocal 只负责"这个组有没有 listener"，
                // 有 listener 也不代表它就是房间接口那个组，所以拿到端口后再真打一次
                // /room/get_list_by_ids 才算数。结果全部留在 [diag] 里。
                //
                // **为什么要重试**：2026-10-29 实测，listener 刚 bind 完的那一两秒里
                // 转发链路还没接通，`kiwi_go` 会先回连接超时（-1）、其它组回 404，
                // 过几秒同一个端口就正常回 200 了。只探一轮会把"其实能用"误判成失败
                // （表现就是隧道面板红着，但直播源自己扫端口反而能拿到数据）。
                val tries = StringBuilder("init=$code")
                var got = false
                // 兜底：探测没命中但确实有组给出了端口，就先记下来 ——
                // 直播源自己还会再验证一次，比在这里判死强
                var fallback: Triple<String, Int, String>? = null
                rounds@ for (round in 0 until 3) {
                    for (g in GROUPS) {
                        val c = KiwiSdk.serverToLocal(context, g)
                        tries.append(" | ").append(g).append('=')
                        if (c != 0 || KiwiSdk.port <= 0) {
                            tries.append(c)
                            continue
                        }
                        val p = KiwiSdk.port
                        if (fallback == null) fallback = Triple(KiwiSdk.host, p, g)
                        val rc = probeRoom(p)
                        tries.append(p).append(':').append(rc).append(if (rc == 200) "✓" else "?")
                        if (rc == 200) {
                            host = KiwiSdk.host
                            port = p
                            group = g
                            got = true
                            break@rounds
                        }
                    }
                    if (!got) {
                        tries.append(" | (第${round + 1}轮无命中)")
                        delay(1500)
                    }
                }
                diag = tries.toString()
                if (!got) {
                    val fb = fallback
                    if (fb == null) {
                        fail("所有组都没给出端口：$tries")
                        return@withContext false
                    }
                    // 有端口但没探通：照样交出去，让直播源去用（它会自己验证真假）
                    host = fb.first
                    port = fb.second
                    group = fb.third
                    prefs.tunnelPort = port
                    state = State.READY
                    message = "隧道已起 ${host}:$port（${group}，未验证）"
                    return@withContext true
                }
                if (!got) {
                    fail("没有组挂 /room 前缀：$tries")
                    return@withContext false
                }

                prefs.tunnelPort = port
                state = State.READY
                message = "隧道就绪 $host:$port（$group）"
                true
            }
        } catch (t: Throwable) {
            fail("异常：${t.javaClass.simpleName} ${t.message}")
            false
        } finally {
            starting = false
        }
    }

    /** 重来一次：清掉 SDK 的失败态再走一遍（SDK 只在 init 失败时才允许换 key 重试） */
    suspend fun restart(appKey: String = prefs.kiwiAppKey): Boolean {
        KiwiSdk.reset()
        state = State.IDLE
        group = ""
        return start(appKey)
    }

    private fun fail(msg: String) {
        state = State.FAILED
        message = msg
        port = 0
    }

    // ------------------------------------------------------------------ 探针
    /**
     * 打一发 `/room/get_list_by_ids` 看这个端口到底挂了哪个 vhost。
     *
     * 判据和 [com.duocai.live.data.LiveRepo.probePort] 一致：只有对的 vhost 才会回
     * `{"code":0,"rooms":[...]}`。错的 vhost 会被 nginx 直接 404（HTML），
     * 或者只回 `{"code":-1,"msg":"ids is null"}` 这种空壳。
     *
     * @return HTTP 状态码（200 = 这个端口就是 /room 的 vhost）；-1 = 连不上/超时
     */
    private fun probeRoom(port: Int): Int {
        val id = sampleRoomId() ?: return -1
        return runCatching {
            val conn = (URL("http://127.0.0.1:$port/room/get_list_by_ids")
                .openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 2000
                conn.readTimeout = 3000
                conn.doOutput = true
                // 这几个头照抄 Dart HttpClient，缺了服务端会挑刺
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("accept-encoding", "gzip")
                conn.setRequestProperty("user-agent", "Dart/3.10 (dart:io)")
                conn.outputStream.use { it.write("""{"ids":"$id"}""".toByteArray()) }

                val code = conn.responseCode
                if (code !in 200..299) return code
                val stream = conn.inputStream ?: return -1
                var raw = stream.use { it.readBytes() }
                if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    raw = GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                }
                // 状态 200 还不够：有的 vhost 会回一个 200 的空 JSON 空壳
                val text = String(raw, Charsets.UTF_8)
                if (text.contains("\"rooms\"")) 200 else -2
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1)
    }

    /**
     * 拿一个**真实**房间号当探针样本。
     *
     * 不能用瞎编的 id：服务端对不存在的 id 也回 200 + 空 rooms，
     * 那就分不出"vhost 对但 id 错"和"vhost 错"了。
     * 顺序：先 filesDir（跑过一次就有，可能已增量），再 assets 里的种子。
     */
    private fun sampleRoomId(): Long? {
        runCatching {
            val f = File(context.filesDir, "room_ids.txt")
            if (f.exists()) {
                f.useLines { lines ->
                    lines.firstOrNull { it.trim().toLongOrNull() != null }
                        ?.trim()?.toLongOrNull()?.let { return it }
                }
            }
        }
        return runCatching {
            context.assets.open("room_ids.txt").bufferedReader().use { r ->
                r.lineSequence().map { it.trim() }.firstOrNull { it.toLongOrNull() != null }
                    ?.toLongOrNull()
            }
        }.getOrNull()
    }

    /**
     * 逐个试候选 appKey，返回可读的结果串。
     *
     * 为什么要这个：appKey 是从源 App 内存里 dump 出来的，候选不止一个。
     * SDK 的 `Init` 只在**失败**时才允许换 key 重试
     * （成功后会把 `init_appkey` 清空并直接 return 缓存的 `init_code`），
     * 所以这个循环天然只能"失败就试下一个"，一旦某个返回 0 就停。
     */
    suspend fun probeAll(): String = withContext(Dispatchers.IO) {
        if (!KiwiSdk.abiSupported()) {
            return@withContext KiwiSdk.abiReason()
        }
        if (!KiwiSdk.ensureLoaded(context)) {
            return@withContext "SDK 加载失败：${KiwiSdk.lastError}"
        }
        val sb = StringBuilder()
        for (k in CANDIDATES) {
            KiwiSdk.reset()
            val c = KiwiSdk.init(context, k)
            sb.append(k.take(10)).append("…=").append(c)
            if (c == 0) {
                KiwiSdk.restartAllServer(context)
                delay(1500)
                // 换 key 之后组表也可能变，重新走一遍组探测
                var hit: Pair<String, Int>? = null
                for (g in GROUPS) {
                    if (KiwiSdk.serverToLocal(context, g) == 0 && KiwiSdk.port > 0 &&
                        probeRoom(KiwiSdk.port) == 200
                    ) {
                        hit = g to KiwiSdk.port
                        break
                    }
                }
                if (hit != null) {
                    initCode = 0
                    state = State.READY
                    host = KiwiSdk.host
                    port = hit.second
                    group = hit.first
                    message = "隧道就绪 $host:$port（$group）"
                    sb.append(" port=").append(hit.second).append(" group=").append(hit.first)
                } else {
                    sb.append(" 组探测失败")
                }
                break
            }
            sb.append("  ")
        }
        sb.toString()
    }

    private companion object {
        /**
         * 13 个真实 vhost 组名 —— 2026-10-29 从源 App 的 native 堆里全量扫出来的。
         *
         * 顺序是按"最可能是业务网关"排的：`go` 看着像网关入口，`selly` / `ssg` / `rz`
         * 是业务侧，`cdn` / `install` 是静态资源，`pay` 是支付，`im` 是长连接。
         *
         * 注意 **没有 `kiwi_live`** —— `http://kiwi_live/tunnel/…` 里那个 `kiwi_live`
         * 是 Dart 侧自己起的伪域名（走本机 listener 的 Host 头），SDK 的组表里不存在它，
         * 问它只会拿到 -5。放在末尾当对照，方便在 [diag] 里一眼看出基线。
         */
        val GROUPS = listOf(
            "kiwi_go", "kiwi_selly", "kiwi_ssg", "kiwi_rz", "kiwi_hd",
            "kiwi_im", "kiwi_whd", "kiwi_bobi", "kiwi_kd", "kiwi_cdn",
            "kiwi_install", "kiwi_pay", "kiwi_yx",
            "kiwi_live"
        )

        /**
         * 候选 appKey，按可能性排序。
         *
         * 第一个是实测可用的真值（从源 App Dalvik 堆 dump 出来的 1432 字符 base64，
         * 是 AES 密文不是口令，`Init` 内部自己解密再喂给 `ParseAppkey`）。
         * 后面几个是早期从 `libapp.so` 对象池里高熵扫描出的误报，留着只为兼容旧日志。
         */
        val CANDIDATES = listOf(
            "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4",
            "fOes34Zne6uYRwId2gVe7A",
            "nZ8h4drPrYCVjtsqqRF1gw",
            "0JirRihgtC6i1kalOn",
            "GyMkSEahKHcLdDmsvzZ"
        )
    }
}
