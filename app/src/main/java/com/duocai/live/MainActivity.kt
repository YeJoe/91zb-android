package com.duocai.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.duocai.live.ui.RootScreen
import com.duocai.live.ui.theme.TmTheme

class MainActivity : ComponentActivity() {

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

    override fun onDestroy() {
        // 真正退出才释放解码器；只是转屏/重建就留着，回来能秒开
        if (isFinishing) applicationContext.app.player.release()
        super.onDestroy()
    }
}
