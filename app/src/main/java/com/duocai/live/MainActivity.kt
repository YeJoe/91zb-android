package com.duocai.live

import android.app.PictureInPictureParams
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

    /** 进画中画。直播基本都是竖屏 9:16，宽高比跟着定，缩成小窗不会两边留大黑边。 */
    fun enterPip() {
        // PictureInPictureParams 是 API 26 才有的，minSdk 是 24，必须显式挡一层
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipSupported()) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /** 播放页开着时按 Home，自动缩成小窗继续看（设置里可关） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = applicationContext.app
        if (!inPip && c.autoPip && c.openIndex >= 0 && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onDestroy() {
        // 真正退出才释放解码器；只是转屏/重建就留着，回来能秒开
        if (isFinishing) applicationContext.app.player.release()
        super.onDestroy()
    }
}
