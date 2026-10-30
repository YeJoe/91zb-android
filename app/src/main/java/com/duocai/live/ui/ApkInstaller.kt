package com.tm.live.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内「覆盖安装」。
 *
 * 为什么不是静默安装：静默安装要 root / 系统签名，普通应用做不到。
 * 能做的是把 APK 交给系统安装器，并保证是 **覆盖安装**（同包名同签名，
 * `adb install -r` 等价）—— 这样登录态、设置、收藏全都留着，不会像卸载重装
 * 那样每次都要重新配一遍。
 *
 * 流程：
 * ```
 * 用户选一个 .apk（SAF / 文件管理器）
 *   → 复制到 cache/update/update.apk（SAF 的 uri 权限是临时的，不能直接交给安装器）
 *   → FileProvider 换成 content://
 *   → ACTION_VIEW + application/vnd.android.package-archive
 *   → 系统弹「要更新此应用吗？」→ 安装（数据保留）
 * ```
 */
object ApkInstaller {

    private fun authority(ctx: Context) = ctx.packageName + ".files"

    /**
     * 从 SAF 的 uri 安装。
     *
     * @return 给 UI 的提示文案；失败时是异常 message
     */
    fun installFromUri(ctx: Context, uri: Uri): String = runCatching {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        // 旧包先删掉：同名覆盖写，避免上次半个文件残留
        val apk = File(dir, "update.apk").apply { if (exists()) delete() }
        val input = ctx.contentResolver.openInputStream(uri)
            ?: return@runCatching "读不到这个文件（可能没有访问权限）"
        input.use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
        if (apk.length() < 1024L) return@runCatching "这个文件不是 APK"

        if (!canInstall(ctx)) {
            requestInstallPermission(ctx)
            return@runCatching "请先允许「安装未知应用」，再点一次"
        }
        launch(ctx, apk)
        "已交给系统安装器：覆盖安装，登录态和设置都保留"
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
}
