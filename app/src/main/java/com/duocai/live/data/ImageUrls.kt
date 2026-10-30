package com.tm.live.data

/**
 * 图床 URL 归一化。
 *
 * 2026-10-30 实测（`tools/_check_mirror.py`，7/7 一致）：
 * 同一张图在 CloudFront 上有**两条路径**，长度一模一样，只差 32 字节的加密头：
 *
 * ```
 * /images/480/u/8200/9833/9/head/a.jpg      -> <mode iv="...">3</mode> + 密文   (解不开就是透明)
 * /static/upload/u/8200/9833/9/head/a.jpg   -> 明文 JPEG/PNG（原图）
 *
 * /files/admin_res/2026021007/pic/a.png     -> 加密流
 * /static/upload/admin_res/2026021007/pic/a.png -> 明文 PNG
 * ```
 *
 * 所以根本不需要复刻 Rabbit 解密：把加密形态的路径换成 `/static/upload/…` 镜像即可。
 * 密文 = 32 字节头 + 明文（纯流加密、无填充），长度差恒为 32。
 */
object ImageUrls {

    private val IMAGES_WIDTH = Regex("""/images/\d+/""")
    private const val UPLOAD = "/static/upload/"

    /** 加密路径 → 明文镜像路径；本来就可用的 URL 原样返回（幂等）。 */
    fun plain(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u
        // /images/<宽>/… -> /static/upload/…
        IMAGES_WIDTH.find(u)?.let { m ->
            return u.replaceRange(m.range, UPLOAD)
        }
        // /files/admin_res/… -> /static/upload/admin_res/…
        if (u.contains("/files/admin_res/")) {
            return u.replace("/files/admin_res/", UPLOAD + "admin_res/")
        }
        return u
    }

    /** 已经是明文镜像了吗（用于判断要不要重写缓存） */
    fun isPlain(url: String): Boolean = url.contains(UPLOAD) && !IMAGES_WIDTH.containsMatchIn(url)
}
