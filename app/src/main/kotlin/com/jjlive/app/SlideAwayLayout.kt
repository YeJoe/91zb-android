package com.jjlive.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * 可以「右滑甩走」的聊天面板。
 *
 * 竖直播放页里聊天区固定占着下半屏，看画面时碍事；这里给面板加一层水平手势：
 *  · 右滑：面板 1:1 跟手并同步变透明，越滑越透，能透出后面的画面；
 *  · 松手超过 30% 宽度或速度够快 → 收起（回调 onDismiss，由播放页切全屏 + 显示恢复胶囊）；
 *  · 没到阈值 → 弹回原位。
 *
 * 只认「水平明显大于垂直」的滑动，竖向仍然交给父容器 SwipeLayout 切直播间，
 * 所以聊天列表照常滚动、上下切间照常生效。
 */
class SlideAwayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 甩出去了。播放页在这里切形态、显示恢复按钮。 */
    var onDismiss: (() -> Unit)? = null

    var dragEnabled = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val tracker = VelocityTracker.obtain()

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastDx = 0f

    private fun reset() {
        dragging = false
        lastDx = 0f
        tracker.clear()
    }

    /** 滑走/被外部隐藏后恢复原状，下次显示时不带残留位移。 */
    fun resetTransform() {
        animate().cancel()
        translationX = 0f
        alpha = 1f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled || visibility != VISIBLE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downX = ev.x
                downY = ev.y
                tracker.addMovement(ev)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 必须是「往右、且水平远大于垂直」才算甩走；斜一点的上下滑留给切间
                if (!dragging && dx > slop && abs(dx) > abs(dy) * 1.6f) {
                    dragging = true
                    lastDx = dx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downX = ev.x
                downY = ev.y
                tracker.addMovement(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(ev)
                val dx = ev.x - downX
                if (!dragging && dx > slop) dragging = true
                if (dragging) {
                    lastDx = max(0f, dx)
                    translationX = lastDx
                    // 位移过半就已近乎全透明，手感上"滑一下就没了"
                    alpha = (1f - (lastDx / (width.coerceAtLeast(1) * 0.62f))).coerceIn(0.08f, 1f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                tracker.addMovement(ev)
                tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                val vx = tracker.xVelocity
                val far = lastDx > width * 0.3f
                val fast = vx > 620 && lastDx > slop * 2
                if (dragging && (far || fast)) dismiss()
                else springBack()
                reset()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                springBack()
                reset()
                return true
            }
        }
        return true
    }

    private fun dismiss() {
        animate()
            .translationX(width.toFloat())
            .alpha(0f)
            .setDuration(170)
            .withEndAction {
                visibility = GONE
                resetTransform()
                onDismiss?.invoke()
            }
            .start()
    }

    fun springBack() {
        animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(190)
            .start()
    }

    override fun onDetachedFromWindow() {
        tracker.recycle()
        super.onDetachedFromWindow()
    }
}
