package com.tm.live

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tm.live.ui.RootScreen
import com.tm.live.ui.theme.TmTheme

class MainActivity : ComponentActivity() {

    /** 设备/系统是否支持画中画（API 26+ 且系统带这个 feature） */
    fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = applicationContext.app
        setContent {
            TmTheme(mode = container.themeMode) {
                RootScreen(container)
            }
        }
    }

    /**
     * 统一的画中画参数。
     *
     * 直播基本都是竖屏 9:16，宽高比跟着定，缩成小窗不会两边留大黑边。
     *
     * `setSeamlessResizeEnabled(false)`（API 31+）很关键：默认开启时，系统在
     * 「小窗 → 全屏」那一帧是直接拉伸上一帧画面的；如果此时解码器还没跟上新尺寸，
     * 就会卡在拉伸帧上，看起来像“点了展开但界面死了”。关掉它让系统老老实实重新
     * 测量布局。
     *
     * `setSourceRectHint` 同样是治「展开不对」的：告诉系统“我这一屏里哪块区域是画面”，
     * 进出小窗的动画就从这块区域缩放，而不是从整个窗口缩放。不设的话系统按窗口 bounds
     * 猜，横竖屏比例不一致时会多出一圈黑色过渡帧，看着就像小窗外面蒙了一层阴影。
     */
    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        pipSourceRect()?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 关掉「无缝缩放」：默认开启时小窗→全屏那一帧直接拉伸上一帧画面，
            // 解码器没跟上就卡在拉伸帧上，看起来像「点了展开但界面死了」。
            builder.setSeamlessResizeEnabled(false)
            // 不用系统 autoEnter：本 App 只有用户主动点「浮窗」才进小窗，
            // 退到桌面不自动缩窗（这里显式关掉，别依赖系统默认值）。
            builder.setAutoEnterEnabled(false)
        }
        return builder.build()
    }

    /**
     * 画面在当前屏幕上的位置，供 `setSourceRectHint` 用。
     *
     * 取窗口内居中的 9:16 区域：和 PiP 的宽高比一致，展开时不会出现比例不匹配的黑边。
     * 窗口还没测量完（宽高为 0）时返回 null，让系统自己兜底。
     */
    private fun pipSourceRect(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val view = window?.decorView ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        // 9:16 的宽度上限，超过就按高度反算，保证不会超出窗口
        val targetW = minOf(w, (h * 9f / 16f).toInt()).coerceAtLeast(1)
        val left = loc[0] + (w - targetW) / 2
        return Rect(left, loc[1], left + targetW, loc[1] + h)
    }

    /**
     * 播放页进出时同步画中画参数（宽高比、sourceRectHint、关闭无缝缩放）。
     *
     * 进小窗只能由用户点播放页顶栏的「浮窗」触发（[enterPip]），退到桌面**不会**
     * 自动缩窗 —— `setAutoEnterEnabled(false)` 已把系统那条路关死。
     */
    fun syncPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams() ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    /** 用户主动进画中画（播放页顶栏「浮窗」按钮，唯一入口） */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams() ?: return
        // 已经在里面了就别重复进，重复调用会让窗口状态机错乱
        if (isInPictureInPictureMode) return
        runCatching { enterPictureInPictureMode(params) }
    }

    /**
     * 小窗 → 全屏。
     *
     * 两条路先后走，哪条先成算哪条：
     *  1. `moveTaskToFront`：把整个任务拉回前台，系统会顺势退出 PiP 并回调
     *     `onPictureInPictureModeChanged(false)`。播放器不重建，画面接着放。
     *  2. `startActivity(REORDER_TO_FRONT)`：部分 ROM / 模拟器桌面不认第 1 条
     *     （尤其是 PiP 由 SystemUI 的 PipMenuView 托管时），再用这个把 Activity
     *     顶回来。单 Activity + singleTop，不会多开一份。
     *
     * 之前只有第 2 条，且第 1 条不存在，所以在小窗里点还原按钮经常「没反应」——
     * Activity 本来就是栈顶，REORDER_TO_FRONT 等于空操作，系统仍认为在 PiP 里。
     */
    fun exitPipToFull() {
        if (!isInPictureInPictureMode) return

        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, 0)
        }

        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        }
    }

    /**
     * 前后台标记。
     *
     * 列表的在播探活要发几百条 HTTP，退到后台还跑就是白烧电，所以容器拿这个
     * 标记当闸门（[com.tm.live.AppContainer.foreground]）。回到前台时容器会
     * 让列表重新上报一次可见区间，补探一轮。
     */
    override fun onResume() {
        super.onResume()
        applicationContext.app.foreground = true
    }

    override fun onPause() {
        applicationContext.app.foreground = false
        super.onPause()
    }

    /**
     * 退到后台就暂停播放。
     *
     * 不自动进小窗之后，按 Home 会直接离开界面：这时候如果还接着解码、还在出声，
     * 就是纯浪费流量。**PiP 中不会走 onStop**（窗口仍然可见），所以小窗里照常播。
     */
    override fun onStop() {
        if (!isInPictureInPictureMode) applicationContext.app.player.pause()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val c = applicationContext.app
        // 唯一真相源移到容器里（播放页和 RootScreen 的返回键都要读它）
        c.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            // 回到全屏后再补一次参数，但要**延后到下一帧**：在这个回调里同步调
            // setPictureInPictureParams 会跟系统的展开事务抢状态机，轻则展开动画卡住，
            // 重则系统把窗口又按 PiP 处理一次，表现就是「点了展开，闪一下又回小窗」。
            window?.decorView?.post { syncPip() }
        }
    }

    override fun onDestroy() {
        // 真正退出才释放解码器；只是转屏/重建就留着，回来能秒开
        if (isFinishing) applicationContext.app.player.release()
        super.onDestroy()
    }
}
