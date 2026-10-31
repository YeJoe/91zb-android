package com.tm.live.data.xhm

import com.tm.live.data.Prefs

/** 把 [Prefs] 里小红帽相关的字段包成一组只读属性，供数据层读取。 */
class XhmPrefs(private val p: Prefs) {
    val token: String get() = p.xhmToken
    val userId: String get() = p.xhmUserId
    val baseUrl: String get() = p.xhmBaseUrl
    val imgBase: String get() = p.xhmImgBase
    val demo: Boolean get() = p.xhmDemo
}
