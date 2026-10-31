package com.tm.live.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内「自更新」：只覆盖安装**本应用自己**。
 *
 * ## 为什么不再让用户随便选一个 APK
 *
 * 之前的入口是通用的「选任意 APK → 交给系统安装器」，两个问题：
 * 1. 选错文件会装上别的应用，用户根本分不清；
 * 2. 通用的安装入口等于把「装任何东西」的能力暴露在设置页里，没有必要。
 *
 * 现在只做一件事：在固定几个目录里找**包名等于本应用、签名也对得上**的更新包，
 * 找到就一键覆盖安装。装完登录态 / 设置 / 收藏全保留（等价 `adb install -r`），
 * 不用重新配置。
 *
 * ## 为什么"覆盖安装"不需要额外权限
 *
 * 静默安装要 root 或系统签名，普通应用做不到。我们能做的是把 APK 交给系统安装器，
 * 由用户在系统弹窗里点确认。同包名同签名时系统走的就是"更新"分支，数据保留。
 *
 * ## 更新包放哪
 *
 * 按顺序找（找到多个取 versionCode 最大的）：
 * ```
 * Android/data/com.tm.live/files/update/   ← 最省事，无需任何存储权限
 * Android/data/com.tm.live/files/
 * /sdcard/Download/
 * ```
 *
 * Android 11 起，别的应用写进 `Download` 的文件本应用读不到（分区存储），
 * 那种情况会明确提示把包放到第一条路径下，而不是含糊地报"没找到"。
 */
object ApkInstaller {

    /** 一个通过校验的更新包 */
    data class Update(
        val apk: File,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        /** 与本机已装版本的比较：>0 更新，0 同版本，<0 回退 */
        val delta: Long
    )

    private const val MIN_APK_BYTES = 1024L * 1024L

    private fun authority(ctx: Context) = ctx.packageName + ".files"

    /** 候选目录，顺序即优先级；只保留真实存在且可读的 */
    fun searchDirs(ctx: Context): List<File> {
        val list = ArrayList<File>()
        ctx.getExternalFilesDir(null)?.let {
            list += File(it, "update")
            list += it
        }
        runCatching { list += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
        list += File("/sdcard/Download")
        // distinctBy canonicalPath：上面几条在多数机型上会指向同一个物理目录
        return list.filter { it.isDirectory && it.canRead() }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    /**
     * 扫描更新包。
     *
     * @return 最合适的那个（versionCode 最大）；没找到返回 null
     */
    fun find(ctx: Context): Update? {
        val mine = installedVersionCode(ctx)
        val mySig = ourSignature(ctx)
        var best: Update? = null
        for (dir in searchDirs(ctx)) {
            val files = runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) && f.length() >= MIN_APK_BYTES }
            }.getOrNull() ?: continue
            for (f in files) {
                val info = archiveInfo(ctx, f) ?: continue
                if (info.packageName != ctx.packageName) continue
                // 签名必须一致，否则系统也装不上（会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE），
                // 提前挡掉能给出一句人话，而不是让用户在系统弹窗里看英文错误
                if (mySig != null) {
                    val sig = info.signatures?.firstOrNull()
                    if (sig == null || !sig.toByteArray().contentEquals(mySig.toByteArray())) continue
                }
                val vc = versionCodeOf(info)
                val cand = Update(
                    apk = f,
                    packageName = info.packageName,
                    versionName = info.versionName ?: "?",
                    versionCode = vc,
                    delta = vc - mine
                )
                if (best == null || cand.versionCode > best.versionCode) best = cand
            }
        }
        return best
    }

    /** 扫描结果的说明文案，给设置页显示 */
    fun scanHint(ctx: Context): String {
        val dirs = searchDirs(ctx)
        if (dirs.isEmpty()) return "没有可读的更新目录"
        return "查找目录：" + dirs.joinToString("、") { it.absolutePath }
    }

    /**
     * 覆盖安装。
     *
     * @return 给 UI 的提示文案
     */
    fun install(ctx: Context, u: Update): String = runCatching {
        if (!canInstall(ctx)) {
            requestInstallPermission(ctx)
            return@runCatching "请先允许「安装未知应用」，再点一次"
        }
        // SAF/外部目录的 uri 权限是临时的，且 FileProvider 只能分享自己 cache 里的文件，
        // 所以统一复制到 cache/update/update.apk 再交给安装器
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val staged = File(dir, "update.apk").apply { if (exists()) delete() }
        u.apk.inputStream().use { i -> staged.outputStream().use { o -> i.copyTo(o) } }
        if (staged.length() < MIN_APK_BYTES) return@runCatching "复制失败：文件不完整"
        launch(ctx, staged)
        val tail = when {
            u.delta > 0L -> "覆盖安装 v${u.versionName}（当前更高版本会被替换）"
            u.delta == 0L -> "覆盖安装同版本 v${u.versionName}"
            else -> "安装较低版本 v${u.versionName}"
        }
        "已交给系统安装器：$tail，登录态和设置都保留"
    }.getOrElse { e -> "安装失败：" + (e.message ?: e.javaClass.simpleName) }

    /** 8.0+ 每个应用单独授权；更早的系统默认允许 */
    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun launch(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    // ------------------------------------------------------------------ 元信息
    @Suppress("DEPRECATION")
    private fun archiveInfo(ctx: Context, f: File): PackageInfo? = runCatching {
        ctx.packageManager.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(ctx: Context): Long = runCatching {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.longVersionCode else i.versionCode.toLong()
    }.getOrDefault(0L)

    @Suppress("DEPRECATION")
    private fun ourSignature(ctx: Context) = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCodeOf(i: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.longVersionCode else i.versionCode.toLong()
}
