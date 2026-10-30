package com.duocai.live

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duocai.live.ui.RootScreen
import com.duocai.live.ui.theme.TmTheme

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
     */
    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 关掉「无缝缩放」：默认开启时小窗→全屏那一帧直接拉伸上一帧画面，
            // 解码器没跟上就卡在拉伸帧上，看起来像「点了展开但界面死了」。
            builder.setSeamlessResizeEnabled(false)
            // 不用系统 autoEnter：和源 App 一致，改由 onUserLeaveHint 里手动进小窗。
            builder.setAutoEnterEnabled(false)
        }
        return builder.build()
    }

    /**
     * 播放页进出时同步画中画参数（宽高比、关闭无缝缩放）。
     *
     * 进入小窗的时机统一走 [onUserLeaveHint] 里的手动 [enterPip]（和源 App 一致）：
     * 手动进小窗的「小窗→全屏卡拉伸帧」问题已经由 `setSeamlessResizeEnabled(false)`
     * 解决（见 [pipParams]），所以不再需要系统 autoEnter。
     */
    fun syncPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams() ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    /** 手动进画中画（API 26–30 的路径，以及播放页顶栏那个按钮） */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams() ?: return
        // 已经在里面了就别重复进，重复调用会让窗口状态机错乱
        if (isInPictureInPictureMode) return
        runCatching { enterPictureInPictureMode(params) }
    }

    /**
     * 小窗 → 全屏（兜底路径）。
     *
     * 正常应该由系统小窗上的「展开」按钮完成；但部分 ROM 上那套 SystemUI 控件会失灵，
     * 所以播放页在小窗里额外画了一个自己的还原按钮，走这里把任务拉回前台。
     * 用 REORDER_TO_FRONT 而不是 NEW_TASK + CLEAR_TOP，避免播放器被销毁重建。
     */
    fun exitPipToFull() {
        if (!isInPictureInPictureMode) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        }
    }

    /** 播放页开着时按 Home，自动缩成小窗继续看（设置里可关）。手动进，和源 App 一致 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = applicationContext.app
        val want = c.autoPip && c.openIndex >= 0
        if (want && !c.inPip && pipSupported()) {
            // setSeamlessResizeEnabled(false) 已关掉拉伸帧，手动进小窗不会卡在半路
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val c = applicationContext.app
        // 唯一真相源移到容器里（播放页和 RootScreen 的返回键都要读它）
        c.inPip = isInPictureInPictureMode
        // 回到全屏后把画中画参数重新同步一次。进出小窗过程中系统会重置
        // 一部分窗口参数，不同步的话下一次「按 Home 自动进小窗」会时灵时不灵。
        if (!isInPictureInPictureMode) {
            syncPip()
        }
    }

    override fun onDestroy() {
        // 真正退出才释放解码器；只是转屏/重建就留着，回来能秒开
        if (isFinishing) applicationContext.app.player.release()
        super.onDestroy()
    }
}
