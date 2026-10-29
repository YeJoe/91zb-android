package com.duocai.live.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 房间数据源。
 *
 * 为什么必须走"远程 JSON"而不是直连接口：见 docs/安卓App设计方案.md §1 ——
 * 房间列表接口只在模拟器隧道里活着，公网 403 / TLS 失败，手机上根本够不着。
 * 所以链路是：GitHub raw 上一份 rooms.json → 本地缓存 → 断网可用。
 */
class RoomRepo(private val context: Context, private val prefs: Prefs) {

    private val cacheFile = File(context.filesDir, "rooms.json")

    /** 冷启动先用缓存渲染，避免白屏 */
    fun loadCache(): SourceData? = runCatching {
        if (!cacheFile.exists()) return@runCatching null
        SourceParser.parse(cacheFile.readText())
    }.getOrNull()

    suspend fun fetch(): Result<SourceData> = withContext(Dispatchers.IO) {
        runCatching {
            val url = prefs.roomsUrl
            if (url.isBlank()) throw IOException("还没填数据源地址")
            val text = httpGet(url)
            val data = SourceParser.parseLoose(text)
                ?: throw IOException("数据源不是合法 JSON（找不到 rooms 数组）")
            cacheFile.writeText(text)
            data
        }
    }

    /** 手动粘贴一份 JSON（换源、离线导入都能用） */
    fun applyPasted(text: String): Result<SourceData> = runCatching {
        val data = SourceParser.parseLoose(text) ?: throw IOException("粘贴的内容里没有房间数组")
        runCatching { cacheFile.writeText(text) }
        data
    }

    private fun httpGet(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                // raw.githubusercontent 对空 UA 会挑刺
                setRequestProperty("User-Agent", "TMLive/1.0 (Android)")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }
}
