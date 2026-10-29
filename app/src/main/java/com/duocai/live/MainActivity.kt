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

    /** 当前是否在画中画里。播放页据此把顶栏/弹幕/手势层全收起来，只留画面。 */
    var inPip by mutableStateOf(false)
        private set

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
    private fun pipParams(autoEnter: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(false)
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    /**
     * 播放页进出时同步画中画参数（是否允许系统自动进入）。
     *
     * **API 31+ 必须走 autoEnter，不要在 onUserLeaveHint 里手动调
     * enterPictureInPictureMode**：手动调用会和「上滑回桌面」的系统手势动画抢时序，
     * 小窗进去之后窗口 transition 停在半路，SystemUI 认为这个窗口不可交互 ——
     * 表现就是小窗上的「展开 / 关闭」按钮点了完全没反应。这就是之前那个 bug。
     */
    fun syncPip(autoEnter: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams(autoEnter) ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    /** 手动进画中画（API 26–30 的路径，以及播放页顶栏那个按钮） */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        val params = pipParams(true) ?: return
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

    /** 播放页开着时按 Home，自动缩成小窗继续看（设置里可关） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = applicationContext.app
        val want = c.autoPip && c.openIndex >= 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 12+ 交给系统 autoEnter：这里只把最新意图同步过去，绝不手动进
            if (want) syncPip(true)
            return
        }

        if (!inPip && want && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // 回到全屏后把 autoEnter 重新按当前状态同步一次。进出小窗过程中系统会重置
        // 一部分窗口参数，不同步的话下一次「按 Home 自动进小窗」会时灵时不灵。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isInPictureInPictureMode) {
            val c = applicationContext.app
            syncPip(c.autoPip && c.openIndex >= 0)
        }
    }

    override fun onDestroy() {
        // 真正退出才释放解码器；只是转屏/重建就留着，回来能秒开
        if (isFinishing) applicationContext.app.player.release()
        super.onDestroy()
    }
}
