package com.tm.live.data.xhm

/**
 * 小红帽 demo 样例数据。
 *
 * 没有会话 token 时让 UI 开箱即用：分类 + 房间列表 + 关注 + 取流地址都从这里拿。
 * 取流地址用的是一条公开、稳定的 HLS 测试流（`test-streams.mux.dev`），
 * 既能验证"播放器共用"确实打通，又不会连到别人的直播间。真要播小红帽自己的 RTMP，
 * 把设置页的 token 填好、关掉 demo 即可。
 */
object XhmDemo {

    private val CATS = listOf(
        XhmCategory("推荐", 320),
        XhmCategory("热门", 210),
        XhmCategory("新人", 96),
        XhmCategory("颜值", 154),
        XhmCategory("歌舞", 88),
        XhmCategory("交友", 132),
        XhmCategory("游戏", 47),
        XhmCategory("语音", 61)
    )

    private val NAMES = listOf(
        "甜心小草莓", "晚风轻语", "渣渣辉", "一颗柠檬", "夜色温柔",
        "奶盖妹妹", "不困的猫", "橘子汽水", "落日飞行", "软糖星球",
        "巷口的风", "盐系少女", "雾里看花", "清欢", "野生小鹿",
        "可乐没气了", "月亮邮递员", "半糖主义", "云朵朵", "阿橘不橘"
    )

    fun home(): XhmHomeData {
        val rooms = NAMES.mapIndexed { i, name ->
            val id = 1772000001L + i * 7
            XhmRoom(
                id = id,
                name = name,
                cat = CATS[(i % (CATS.size - 1)) + 1].name,
                online = (300 + (i * 137) % 4800),
                avatar = "",
                cover = "",
                fans = 1200 + (i * 911) % 90000,
                liveIn = true
            )
        }
        return XhmHomeData(CATS, rooms)
    }

    fun follows(): List<XhmRoom> = home().rooms.take(6)

    fun search(q: String): List<XhmRoom> =
        home().rooms.filter { it.name.contains(q, ignoreCase = true) || it.id.toString().contains(q) }

    /** demo 取流：公开 HLS 测试流，验证播放器共用链路 */
    fun streamFor(roomId: Long): String =
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
}
