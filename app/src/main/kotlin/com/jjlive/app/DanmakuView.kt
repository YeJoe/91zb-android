package com.jjlive.app

import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import java.util.ArrayDeque

/**
 * 弹幕渲染层。
 *
 * 刻意避开 ValueAnimator / Handler，改用 Choreographer 驱动：
 * onDraw 内零分配、不拼字符串，对象池复用。
 *
 * 注意：XML 里引用自定义 View 时，LayoutInflater 只认
 * (Context, AttributeSet) 构造函数，少了它会在 setContentView 时
 * 抛 InflateException（NoSuchMethodException），整个 Activity 直接崩。
 */
class DanmakuView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Item {
        var text: String = ""
        var color: Int = 0
        var x = 0f
        var y = 0f
        var speed = 0f
        var textWidth = 0f
        var alive = false
    }

    private val pool = ArrayDeque<Item>()
    private val active = ArrayList<Item>(64)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.density
        setShadowLayer(2.5f, 1f, 1f, 0x99000000.toInt())
    }

    private val trackCount = 6
    private val trackGap = (paint.textSize * 1.7f)
    private val trackBusyUntil = LongArray(trackCount)

    private var lastFrameNs = 0L

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            val dt = if (lastFrameNs == 0L) 0f else ((ns - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
            lastFrameNs = ns
            step(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCb)
        recycleAll()
    }

    /** 投递一条弹幕（可在任意线程调用）。 */
    fun submit(text: String, color: Int = 0xFFEFEFEF.toInt()) {
        if (text.isBlank()) return
        post {
            if (active.size >= 60) { recycleOldest() }
            val it = obtain()
            it.text = text
            it.color = color
            it.textWidth = paint.measureText(text)
            it.y = nextTrack()
            it.x = width.toFloat()
            it.speed = (width + it.textWidth) / 8000f   // 8 秒滚完
            it.alive = true
            active.add(it)
        }
    }

    private fun step(dt: Float) {
        var i = 0
        while (i < active.size) {
            val it = active[i]
            it.x -= it.speed * dt * 1000f
            if (it.x + it.textWidth < 0) {
                it.alive = false
                active.removeAt(i)
                pool.add(it)
            } else i++
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (i in active.indices) {
            val it = active[i]
            paint.color = it.color
            canvas.drawText(it.text, it.x, it.y + paint.textSize, paint)
        }
    }

    private fun nextTrack(): Float {
        val now = System.currentTimeMillis()
        for (t in 0 until trackCount) {
            if (trackBusyUntil[t] < now) {
                trackBusyUntil[t] = now + 400
                return t * trackGap
            }
        }
        return (now / 400 % trackCount).toInt() * trackGap
    }

    private fun obtain(): Item = pool.poll() ?: Item()

    private fun recycleOldest() {
        if (active.isEmpty()) return
        val it = active.removeAt(0)
        it.alive = false
        pool.add(it)
    }

    private fun recycleAll() {
        for (it in active) pool.add(it)
        active.clear()
    }
}
