package com.duocai.live

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * 封面图的加载器。
     *
     * 单独造它是因为 **图床在部分网络下整段不可达**：`drdue73ptt7o.cloudfront.net`
     * 是 CloudFront 分发，国内直连时 TCP 能连、请求却零响应（实测），
     * 同一 URL 在开了代理的电脑上 200/76KB。所以这里读一个"图片代理"设置，
     * 填了就整条链路走 HTTP 代理，没填就直连（默认行为不变）。
     *
     * 另外把超时压短：不可达时不要让孩子等 10 秒才显示首字兜底。
     */
    override fun newImageLoader(): ImageLoader {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        proxyOf(runCatching { container.prefs.imgProxy }.getOrDefault(""))?.let {
            builder.proxy(it)
        }
        return ImageLoader.Builder(this)
            .okHttpClient { builder.build() }
            .crossfade(true)
            .build()
    }

    /** 设置页改完代理调一下，不用重启 App 就生效 */
    fun reloadImageLoader() {
        runCatching { Coil.setImageLoader(newImageLoader()) }
    }

    /** `host:port` → Proxy；解析不出来就当没配（宁可直连，也别让图片全加载不出来） */
    private fun proxyOf(raw: String): Proxy? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val host = s.substringBeforeLast(':', "")
        val port = s.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return runCatching { Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)) }.getOrNull()
    }
}

/** 全局容器取用：`context.app` */
val Context.app: AppContainer
    get() = (applicationContext as App).container
