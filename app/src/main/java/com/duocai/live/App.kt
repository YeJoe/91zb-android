package com.duocai.live

import android.app.Application
import android.content.Context

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 全局容器取用：`context.app` */
val Context.app: AppContainer
    get() = (applicationContext as App).container
