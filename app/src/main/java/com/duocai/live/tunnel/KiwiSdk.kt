package com.tm.live.tunnel

import android.content.Context
import android.os.Build
import android.os.Process
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
 * ## 加载顺序：**绝对不能先 System.load**
 *
 * 这是踩过的一次真实闪退（整进程 SIGABRT，`try/catch` 拦不住）：
 *
 * ```
 * JNI DETECTED ERROR IN APPLICATION: JNI NewGlobalRef called with pending exception
 *   java.lang.ClassNotFoundException: Didn't find class "com.kiwi.sdk.Kiwi"
 *   on path: DexPathList[[zip file ".../base.apk"], ...]
 *     at Runtime.nativeLoad(String, ClassLoader, Class)
 *     at System.load(String)
 *     at KiwiSdk.ensureLoaded(KiwiSdk.kt:107)
 * ```
 *
 * 原因是 `System.load(path)` 走的是 `Runtime.load0(null, path)`，传给 `JNI_OnLoad`
 * 的 ClassLoader 是**系统 ClassLoader**（只能看到 base.apk）。而 `libkiwi.so` 的
 * `JNI_OnLoad` 里会 `FindClass("com.kiwi.sdk.Kiwi")` 做动态注册 —— 找不到就抛异常，
 * 它没清 pending exception 就接着调 `NewGlobalRef`，ART 直接 abort 掉整个进程。
 *
 * 正确姿势：**让 `Kiwi` 自己的 `<clinit>` 去 `System.loadLibrary("kiwi")`**。
 * 此时 `nativeLoad` 收到的 ClassLoader 是我们构造的 [DexClassLoader]，
 * `JNI_OnLoad` 里的 `FindClass` 才能找到同一份 jar 里的类。
 *
 * 换句话说：不要自己加载 so，只要保证 [DexClassLoader] 的 `librarySearchPath`
 * 指向 so 所在目录，再 `Class.forName("com.kiwi.sdk.Kiwi", true, loader)` 就行。
 *
 * ## 其它已知坑
 *
 * 1. **只有 arm64 版 so**。所以它放在 `jniLibs/arm64-v8a`，让系统按 ABI 把进程
 *    直接拉成 arm64 —— 有 ARM 转译层的 x86 模拟器（`ro.dalvik.vm.native.bridge`）
 *    也能跑，源 App 就是这么跑的。真不给 arm64 的环境，[abiSupported] 提前挡掉。
 * 2. **targetSdk 34+ 不许从可写文件加载代码**。解压出来的 jar 必须置只读，
 *    否则 `DexClassLoader` 直接抛 `SecurityException`。
 * 3. `Init` 是**同步**的，会做网络握手，必须放 IO 线程，主线程调用会 ANR。
 */
object KiwiSdk {

    private const val ASSET_JAR = "kiwi-sdk.jar"
    private const val LIB_NAME = "kiwi"

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

    /**
     * 加载已经走过一遍且失败。
     *
     * 不再重试的理由：`Class.forName` 一旦在 `<clinit>` 里失败，这个类在当前进程里
     * 会被永久标记成 Erroneous，重试必然还是同一个错；而真正危险的路径（JNI 层）
     * 失败时进程已经没了，重试也没有意义。
     */
    @Volatile private var hardFailed = false

    private var kiwi: Class<*>? = null
    private var loader: ClassLoader? = null

    val usable: Boolean get() = initCode == 0 && port > 0

    /**
     * 本机能不能跑 arm64 的 `libkiwi.so`。
     *
     * 注意判据是 `SUPPORTED_ABIS` 里有没有 arm64-v8a，而不是当前进程的 ABI ——
     * x86 机器只要带 ARM 转译层（模拟器 `ro.dalvik.vm.native.bridge`），
     * 装一个含 arm64 native 库的 APK 之后，系统就会把进程直接拉成 arm64。
     */
    fun abiSupported(): Boolean {
        val abis = Build.SUPPORTED_ABIS ?: return false
        return abis.contains("arm64-v8a") && Process.is64Bit()
    }

    /** 给设置页用的一句话解释，说明为什么用不了 */
    fun abiReason(): String =
        "本机 ABI 是 " + (Build.SUPPORTED_ABIS?.joinToString("/") ?: "?") +
            "，没有 arm64-v8a 也没有 ARM 转译层，加载不了 libkiwi.so"

    /**
     * 加载 so + dex，并把 Context 注入 SDK。
     *
     * 幂等：重复调用只做一次。任何一步失败都会把原因写进 [lastError]，
     * 调用方（设置页 / 启动流程）据此决定是否退回"借源 App 端口"的老路子。
     */
    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (dexLoaded) return true
        if (hardFailed) return false
        lastError = ""

        // ---- 0. 架构门禁：必须在任何 dlopen 之前 ----
        if (!abiSupported()) {
            stage = "本机不支持"
            lastError = abiReason()
            hardFailed = true
            return false
        }

        // ---- 1. 解包 jar（必须置只读，见类注释「已知坑 2」）----
        stage = "解包 kiwi-sdk.jar"
        val jar = try {
            prepareJar(ctx)
        } catch (t: Throwable) {
            stage = "jar 解包失败"
            lastError = "解包 kiwi-sdk.jar 失败：${t.javaClass.simpleName} ${t.message}"
            hardFailed = true
            return false
        }

        // ---- 2. so 必须在 nativeLibraryDir 里 ----
        // `libkiwi.so` 现在放在 jniLibs/arm64-v8a，由系统按 ABI 释放到 nativeLibraryDir。
        // 这一步同时是最后一道保险：如果进程被分成了 x86_64（nativeLibraryDir 指向
        // lib/x86_64），这里会直接看不到 so，从而在 dlopen 之前就退出，
        // 不会走到那个会 abort 整个进程的 JNI_OnLoad。
        val libDir = ctx.applicationInfo.nativeLibraryDir ?: ""
        if (libDir.isBlank() || !File(libDir, "lib$LIB_NAME.so").exists()) {
            stage = "缺少 libkiwi.so"
            lastError = "nativeLibraryDir 里没有 libkiwi.so（$libDir）；" +
                "本机很可能没跑在 arm64 上，内置隧道不可用"
            hardFailed = true
            return false
        }

        // ---- 3. 先建 ClassLoader，别自己 load so ----
        stage = "建 DexClassLoader"
        val opt = File(ctx.codeCacheDir, "kiwi-opt").apply { mkdirs() }
        val l = DexClassLoader(
            jar.absolutePath,
            opt.absolutePath,
            // librarySearchPath：Kiwi 的 <clinit> 里那句 System.loadLibrary("kiwi")
            // 就靠这个目录找 libkiwi.so
            libDir,
            javaClass.classLoader
        )
        loader = l

        // ---- 4. 先把 Context 塞进去，再触发 so 加载 ----
        bindContext(ctx)

        // ---- 5. 触发 <clinit>：它会 loadLibrary("kiwi")，JNI_OnLoad 此时能 FindClass ----
        stage = "加载 com.kiwi.sdk"
        return try {
            kiwi = Class.forName("com.kiwi.sdk.Kiwi", true, l)
            soLoaded = true
            dexLoaded = true
            stage = "SDK 就绪"
            true
        } catch (t: Throwable) {
            val cause = (t as? ExceptionInInitializerError)?.cause ?: t
            stage = "SDK 加载失败"
            lastError = "加载 com.kiwi.sdk.Kiwi 失败：${cause.javaClass.simpleName} ${cause.message}"
            hardFailed = true
            false
        }
    }

    /**
     * 把 SDK 认的 Context 换成我们的 Application。
     *
     * 不换也能跑：`ContextHelp.getContext()` 会反射走
     * `ActivityThread.currentActivityThread().getApplication()`。
     * 但那是 hidden API，Android 9+ 有灰名单限制，直接塞更稳。
     *
     * 放在触发 `<clinit>` 之前，是因为加固库的 `JNI_OnLoad` 里也可能要取 Context，
     * 那时还没有 Java 层入口可以补救。
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
     * 启动/重启全部隧道 listener。
     *
     * **这一步不能省**：`Init` 只完成和云端的握手（appKey 校验 + 拿到节点列表），
     * 真正的 `TcpProxy`（本地 listener）是在 `RestartAllServer` 里才起来的。
     * 只 Init 不 Restart 时 `ServerToLocal` 会返回 -5 —— 因为组表还是空的，
     * 内部 `KiwiCore::Dns(group)` 查不到任何节点。源 App 也是 init 完之后再调这个。
     */
    fun restartAllServer(ctx: Context): Boolean {
        if (!ensureLoaded(ctx)) return false
        val k = kiwi ?: return false
        return try {
            k.getMethod("RestartAllServer").invoke(null)
            stage = "已请求重启隧道"
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = "RestartAllServer 抛异常：${cause.javaClass.simpleName} ${cause.message}"
            false
        }
    }

    /** 强制重启某个端口的 listener（`ForceRestartServer(port)`），0 或端口号都可 */
    fun forceRestartServer(ctx: Context, port: Int): Int {
        if (!ensureLoaded(ctx)) return Int.MIN_VALUE
        val k = kiwi ?: return Int.MIN_VALUE
        return try {
            val m = k.getMethod("ForceRestartServer", Int::class.javaPrimitiveType)
            (m.invoke(null, port) as? Int) ?: Int.MIN_VALUE
        } catch (t: Throwable) {
            Int.MIN_VALUE
        }
    }

    /**
     * 覆盖 SDK 的 `init_appname`。
     *
     * SDK 的 `Init` 内部会按"本进程的 Application label##包名"拼出 appname 再传给 native，
     * 云端可能按 appname 下发节点。我们不是源 App，包名对不上，必要时用源 App 的覆盖一下。
     */
    fun applyAppName(ctx: Context, name: String): Int {
        if (!ensureLoaded(ctx)) return Int.MIN_VALUE
        val k = kiwi ?: return Int.MIN_VALUE
        return try {
            val m = k.getMethod("init_appname", String::class.java)
            (m.invoke(null, name) as? Int) ?: Int.MIN_VALUE
        } catch (t: Throwable) {
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

    /**
     * 重置失败态，让下一次 [init] 重新走一遍。
     *
     * 注意不动 [hardFailed]：类加载层面的失败在这个进程里不可恢复，
     * 只有 `Init` 返回码 / 端口这种业务态才值得重试。
     */
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
    private fun prepareJar(ctx: Context): File {
        val jar = File(ctx.filesDir, ASSET_JAR)
        val stamp = File(ctx.filesDir, "$ASSET_JAR.stamp")
        val code = versionStamp(ctx)
        val fresh = jar.exists() && jar.length() > 0 && stamp.exists() &&
            stamp.readText().trim() == code
        if (!fresh) {
            jar.setWritable(true, true)
            ctx.assets.open(ASSET_JAR).use { input ->
                jar.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            stamp.writeText(code)
        }
        jar.setReadOnly()
        return jar
    }

    /**
     * 版本戳：安装/更新过就重新解包 jar。
     *
     * 用 `lastUpdateTime` 而不是 versionCode：调试时同版本会反复覆盖 assets，
     * 只有 lastUpdateTime 每次装包都会变，改了 jar 就一定会重新释放。
     */
    private fun versionStamp(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionCode}-${info.lastUpdateTime}"
    }.getOrDefault("0")
}
