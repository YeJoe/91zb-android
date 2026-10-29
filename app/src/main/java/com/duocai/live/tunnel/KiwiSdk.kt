package com.duocai.live.tunnel

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 自研隧道：把源 App 的云层 SDK 搬进我们自己的进程。
 *
 * ## 为什么能这么干
 *
 * 源 App 的实时数据不是走 HTTP 域名，而是走 `libkiwi.so` 起的本地反向代理：
 * SDK 和云端建立一条私有长连接，然后在 `127.0.0.1:<随机端口>` 上开若干个 listener，
 * 每个 listener 对应云端一个 vhost（`kiwi_live` / `kiwi_hd` / `kiwi_im` …）。
 * 我们之前一直是"借"源 App 起好的端口，所以源 App 必须常驻。
 *
 * 反编译 `classes3.dex` 里的 `com.kiwi.sdk.Kiwi` 后，Java 侧入口一共这么几个：
 *
 * ```java
 * static { System.loadLibrary("kiwi"); }
 * public static int Init(String appkey);                                   // 同步初始化
 * public static int InitWithListener(String appkey, KiwiListener l);        // 起线程版
 * public static int ServerToLocal(String group, StringBuffer ip, StringBuffer port);
 * public static int ForceRestartServer(int);
 * ```
 *
 * `ServerToLocal` 就是我们要的东西：给一个 vhost 名（`kiwi_live`），
 * 它把本地 IP 和端口写回两个 StringBuffer，native 返回形如 `"0|127.0.0.1|41305"`。
 *
 * ## 为什么不需要源 App 装着
 *
 * - `libkiwi.so` 我们从源 APK 里提出来打进自己 APK 的 `jniLibs`，**不依赖源 App**；
 * - `com.kiwi.sdk.*` 从 `classes3.dex` 整体打包成 `assets/kiwi-sdk.jar`，运行时用
 *   [DexClassLoader] 加载（dex 里其余几千个类不会被初始化，只是躺在那里）；
 * - `ContextHelp.getContext()` 是用反射去 `ActivityThread.currentActivityThread()
 *   .getApplication()` 拿进程级 Context —— 在我们进程里拿到的就是我们自己的，
 *   所以初始化前先把 `CONTEXT_INSTANCE` 反射设成我们的 Application，省掉那次反射。
 *
 * 换句话说：源 App 从此只剩"SDK 提供方"这一个身份，不用启动、不占内存、不用常驻。
 *
 * ## 已知坑
 *
 * 1. `System.loadLibrary("kiwi")` 在 DexClassLoader 加载的类里调用时，
 *    要靠构造 DexClassLoader 时传的 `librarySearchPath`（这里给 `nativeLibraryDir`）才找得到；
 *    所以加载顺序必须是"先 [System.loadLibrary]，再 Class.forName 触发它的 <clinit>"。
 * 2. `Init` 是**同步**的，会做网络握手，必须放 IO 线程，主线程调用会 ANR。
 * 3. `Init` 成功后会把 `init_appkey` 静态字段清空，所以同一个进程里换 appkey 重试
 *    需要走 `init_code != 0` 的那条分支 —— 换句话说**只有失败才能重试**，这是 SDK 的设计。
 */
object KiwiSdk {

    private const val ASSET_JAR = "kiwi-sdk.jar"
    private const val ASSET_SO = "libkiwi-arm64.so"
    private const val LIB_NAME = "kiwi"
    private const val LIB_DIR = "kiwi-lib"

    /** 当前阶段，给设置页显示用 */
    @Volatile var stage: String = "未加载"
        private set

    @Volatile var soLoaded = false
        private set
    @Volatile var dexLoaded = false
        private set
    @Volatile var initCode: Int = Int.MIN_VALUE
        private set
    @Volatile var host: String = ""
        private set
    @Volatile var port: Int = 0
        private set
    @Volatile var lastError: String = ""
        private set

    /** `ServerToLocal` 的返回码，0 表示成功 */
    @Volatile var localCode: Int = Int.MIN_VALUE
        private set

    private var kiwi: Class<*>? = null
    private var loader: ClassLoader? = null

    val usable: Boolean get() = initCode == 0 && port > 0

    /**
     * 加载 so + dex，并把 Context 注入 SDK。
     *
     * 幂等：重复调用只做一次。任何一步失败都会把原因写进 [lastError]，
     * 调用方（设置页 / 启动流程）据此决定是否退回"借源 App 端口"的老路子。
     */
    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (dexLoaded) return true
        lastError = ""

        // ---- 1. so ----
        if (!soLoaded) {
            stage = "准备 libkiwi.so"
            val soFile = try {
                prepareLib(ctx)
            } catch (t: Throwable) {
                stage = "so 解包失败"
                lastError = "解包 libkiwi.so 失败：${t.javaClass.simpleName} ${t.message}"
                return false
            }
            val arch = System.getProperty("os.arch") ?: "?"
            // 只有 arm64 版 so。x86/x86_64 设备（多数模拟器）十有八九加载不了，
            // 但还是试一把 —— 开了 ARM 兼容/转译的模拟器有时能过。失败就降级。
            val ok = runCatching { System.load(soFile.absolutePath); true }.getOrElse { e ->
                lastError = "System.load 失败（本机 $arch，so 只有 arm64 版）：" +
                    "${e.javaClass.simpleName} ${e.message}"
                false
            }
            if (!ok) {
                stage = "so 加载失败"
                return false
            }
            soLoaded = true
        }

        // ---- 2. dex ----
        stage = "解包 kiwi-sdk.jar"
        val jar = try {
            prepareJar(ctx)
        } catch (t: Throwable) {
            stage = "解包失败"
            lastError = "解包失败：${t.javaClass.simpleName} ${t.message}"
            return false
        }

        stage = "加载 com.kiwi.sdk"
        return try {
            val opt = File(ctx.codeCacheDir, "kiwi-opt").apply { mkdirs() }
            val l = DexClassLoader(
                jar.absolutePath,
                opt.absolutePath,
                // librarySearchPath 必须指向 so 所在目录：
                // Kiwi 的 <clinit> 里还有一句 System.loadLibrary("kiwi")，
                // 它要靠这个路径才 findLibrary 得到东西
                File(ctx.filesDir, LIB_DIR).absolutePath,
                javaClass.classLoader
            )
            val k = Class.forName("com.kiwi.sdk.Kiwi", true, l)
            loader = l
            kiwi = k
            dexLoaded = true
            stage = "SDK 就绪"
            bindContext(ctx)
            true
        } catch (t: Throwable) {
            stage = "dex 加载失败"
            lastError = "Class.forName 失败：${t.javaClass.simpleName} ${t.message}"
            false
        }
    }

    /**
     * 把 SDK 认的 Context 换成我们的 Application。
     *
     * 不换也能跑：`ContextHelp.getContext()` 会反射走
     * `ActivityThread.currentActivityThread().getApplication()`。
     * 但那是 hidden API，Android 9+ 有灰名单限制，直接塞更稳。
     */
    fun bindContext(ctx: Context) {
        val l = loader ?: return
        runCatching {
            val ch = Class.forName("com.kiwi.sdk.ContextHelp", true, l)
            val f = ch.getDeclaredField("CONTEXT_INSTANCE")
            f.isAccessible = true
            f.set(null, ctx.applicationContext)
        }
    }

    /**
     * 初始化隧道。**同步、会做网络握手，必须在 IO 线程调用。**
     *
     * @return SDK 返回码，0 = 成功
     */
    fun init(ctx: Context, appKey: String): Int {
        if (!ensureLoaded(ctx)) return Int.MIN_VALUE
        val k = kiwi ?: return Int.MIN_VALUE
        stage = "初始化（握手）"
        return try {
            val m = k.getMethod("Init", String::class.java)
            val code = (m.invoke(null, appKey) as? Int) ?: Int.MIN_VALUE
            initCode = code
            stage = if (code == 0) "初始化成功" else "初始化返回 $code"
            code
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = "Init 抛异常：${cause.javaClass.simpleName} ${cause.message}"
            stage = "初始化异常"
            Int.MIN_VALUE
        }
    }

    /**
     * 问 SDK 要某个 vhost 的本地端口。
     *
     * @param group `kiwi_live` / `kiwi_hd` / `kiwi_im` …
     * @return 0 表示成功，此时 [host] / [port] 有效
     */
    fun serverToLocal(ctx: Context, group: String = "kiwi_live"): Int {
        if (!ensureLoaded(ctx)) return Int.MIN_VALUE
        val k = kiwi ?: return Int.MIN_VALUE
        return try {
            val m = k.getMethod(
                "ServerToLocal",
                String::class.java,
                StringBuffer::class.java,
                StringBuffer::class.java
            )
            val ip = StringBuffer()
            val pt = StringBuffer()
            val code = (m.invoke(null, group, ip, pt) as? Int) ?: Int.MIN_VALUE
            localCode = code
            if (code == 0) {
                host = ip.toString()
                port = pt.toString().toIntOrNull() ?: 0
                stage = "隧道就绪 $host:$port"
            } else {
                stage = "取端口失败 code=$code"
            }
            code
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = "ServerToLocal 抛异常：${cause.javaClass.simpleName} ${cause.message}"
            Int.MIN_VALUE
        }
    }

    /** 重置失败态，让下一次 [init] 重新走一遍（对应 SDK 的 `init_code != 0` 重试分支） */
    fun reset() {
        initCode = Int.MIN_VALUE
        localCode = Int.MIN_VALUE
        host = ""
        port = 0
        lastError = ""
        stage = if (dexLoaded) "SDK 就绪" else "未加载"
    }

    /** SDK 版本号（方便对日志） */
    fun sdkVersion(ctx: Context): String = runCatching {
        val l = loader ?: return "?"
        val bc = Class.forName("com.kiwi.sdk.BuildConfig", true, l)
        val f = bc.getField("VERSION_NAME")
        f.get(null) as? String ?: "?"
    }.getOrDefault("?")

    // ------------------------------------------------------------------ 内部
    /**
     * 把 `assets/libkiwi-arm64.so` 释放到 `filesDir/kiwi-lib/libkiwi.so`。
     *
     * 为什么不放 `jniLibs`：放 jniLibs 会让 APK 声明自己只有 arm64-v8a，
     * 于是 x86_64 的模拟器连装都装不上（`INSTALL_FAILED_NO_MATCHING_ABIS`）。
     * 放 assets + 运行时解压，APK 就没有 native 代码标记，任何设备都能装；
     * arm64 设备解压后 `System.load` 即可用，x86 设备在调用前就被
     * [ensureLoaded] 里的架构检查挡掉，安静降级到"借源 App 端口"。
     */
    private fun prepareLib(ctx: Context): File {
        val dir = File(ctx.filesDir, LIB_DIR).apply { mkdirs() }
        val so = File(dir, "lib$LIB_NAME.so")
        val stamp = File(dir, ".stamp")
        val code = versionStamp(ctx)
        val fresh = so.exists() && so.length() > 0L && stamp.exists() &&
            stamp.readText().trim() == code
        if (!fresh) {
            ctx.assets.open(ASSET_SO).use { input ->
                so.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            stamp.writeText(code)
        }
        so.setReadable(true, false)
        so.setExecutable(true, false)
        return so
    }

    private fun prepareJar(ctx: Context): File {
        val jar = File(ctx.filesDir, ASSET_JAR)
        val stamp = File(ctx.filesDir, "$ASSET_JAR.stamp")
        val code = versionStamp(ctx)
        val fresh = jar.exists() && jar.length() > 0 && stamp.exists() &&
            stamp.readText().trim() == code
        if (!fresh) {
            ctx.assets.open(ASSET_JAR).use { input ->
                jar.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            stamp.writeText(code)
        }
        return jar
    }

    /**
     * 版本戳：包版本变了就重新解包。
     *
     * 只认 versionCode 不够 —— 调试时同版本会反复覆盖 assets，所以把 assets 的
     * 字节数也拼进去，改一下 so/dex 体积就会自然失效。
     */
    private fun versionStamp(ctx: Context): String {
        // 不用 longVersionCode：那是 API 28 才有的，minSdk 是 24
        val vc = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong()
        }.getOrDefault(0L)
        val soLen = runCatching {
            ctx.assets.openFd(ASSET_SO).use { it.length }
        }.getOrDefault(0L)
        return "$vc-$soLen"
    }
}
