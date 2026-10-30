package com.tm.live

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
     * 图片缓存对象**必须跨 ImageLoader 重建复用**。
     *
     * [reloadImageLoader] 每次都会造一个新的 ImageLoader；缓存对象如果跟着一起新建，
     * 用户每改一次代理设置，整屏封面就得重新下一遍 —— 这是"回到列表又从头加载"
     * 的主要来源之一。缓存本身与 Http 客户端无关，所以单独拎出来做单例。
     *
     * 内存缓存 25% 是 Coil 的默认量级，只装"解码后的位图"，LRU 淘汰；
     * 列表是 Lazy 布局，实际占用只跟可见格子数走，不会随房间总数涨。
     */
    private val memoryCache: MemoryCache by lazy {
        MemoryCache.Builder(this).maxSizePercent(0.25).build()
    }

    /**
     * 磁盘缓存放大到 256MB（Coil 默认只占可用空间 2%，小容量机器上几十 MB 就顶满，
     * 一满就开始淘汰，表现就是"滑过去再滑回来又要重新下"）。
     * 封面一张 30~80KB，256MB 够放几千张。
     */
    private val diskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("image_cache"))
            .maxSizeBytes(256L * 1024 * 1024)
            .build()
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
            .memoryCache(memoryCache)
            .diskCache(diskCache)
            // 图床给静态图带的是短 max-age / no-cache，按响应头尊重的盘缓存形同虚设；
            // 但这些文件名本身是内容哈希，内容不会变，所以放心无视缓存头。
            .respectCacheHeaders(false)
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
