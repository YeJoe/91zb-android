package com.duocai.live.tunnel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duocai.live.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

                // 握手刚结束时 ServerToLocal 有时还拿不到端口，重试几次
                var c = Int.MIN_VALUE
                for (i in 0 until 4) {
                    c = KiwiSdk.serverToLocal(context, GROUP)
                    if (c == 0 && KiwiSdk.port > 0) break
                    if (i < 3) delay(700)
                }
                if (c != 0 || KiwiSdk.port <= 0) {
                    fail("ServerToLocal 返回 $c，没拿到端口")
                    return@withContext false
                }

                host = KiwiSdk.host
                port = KiwiSdk.port
                prefs.tunnelPort = port
                state = State.READY
                message = "隧道就绪 $host:$port"
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
        return start(appKey)
    }

    private fun fail(msg: String) {
        state = State.FAILED
        message = msg
        port = 0
    }

    /**
     * 逐个试候选 appKey，返回可读的结果串。
     *
     * 为什么要这个：appKey 是从 `libapp.so` 的对象池里"考古"出来的，
     * 候选不止一个。SDK 的 `Init` 只在**失败**时才允许换 key 重试
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
                val lc = KiwiSdk.serverToLocal(context)
                sb.append(" port=").append(KiwiSdk.port).append(" (local=$lc)")
                initCode = 0
                state = State.READY
                port = KiwiSdk.port
                host = KiwiSdk.host
                message = "隧道就绪 $host:$port"
                break
            }
            sb.append("  ")
        }
        sb.toString()
    }

    private companion object {
        /** 房间列表挂在 `kiwi_live` 这个 vhost 上（`http://kiwi_live/tunnel/…`） */
        const val GROUP = "kiwi_live"

        /**
         * 候选 appKey，按可能性排序。
         *
         * 前两个来自 blutter 从 `libapp.so` 对象池 dump 出的 `main.dart` 常量区
         * （和 `"kiwi_"`、`http://kiwi_live/tunnel/a033b072?z=1&nobase64=1` 紧挨着），
         * 后几个是本文件同一段高熵扫描出来的漏网之鱼。
         */
        val CANDIDATES = listOf(
            "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4",
            "fOes34Zne6uYRwId2gVe7A",
            "nZ8h4drPrYCVjtsqqRF1gw",
            "0JirRihgtC6i1kalOn",
            "GyMkSEahKHcLQdDmsvzZ"
        )
    }
}
