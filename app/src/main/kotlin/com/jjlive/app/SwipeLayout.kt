package com.jjlive.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 竖直播放页的「上下滑动切间」容器。
 *
 * 为什么不用 OnTouchListener：监听器只有在「没有子 View 消费 DOWN」时才会收到事件，
 * 所以落点在聊天列表上时手势会被 RecyclerView 整个吃掉，切间全靠运气 —— 这正是之前
 * 成功率低的原因。这里改成父容器拦截（onInterceptTouchEvent）模型：
 *
 *  1. DOWN 先照常派发给子 View（按钮点击、聊天列表滚动都不受影响）；
 *  2. MOVE 时判断是否为「竖向意图」，若是：
 *       - 落点在可滚动区域且该方向还能滚 → 不拦，交给子 View；
 *       - 其余情况 → 拦截（子 View 收到 CANCEL），由本容器接管拖拽；
 *  3. 松手回调位移与速度，由调用方决定切间还是回弹。
 *
 * 判定只要求 |dy| > |dx|，不再乘 1.3 的系数 —— 稍微斜一点的滑动现在也能触发。
 */
class SwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 手指落点是否在「会自己滚动的子 View」上：(x, y) 用本容器的坐标系。 */
    var inScrollableArea: ((Float, Float) -> Boolean)? = null

    /** 可滚动子 View 在这个方向上是否还能滚：dy<0 表示手指上移。 */
    var childCanScroll: ((Int) -> Boolean)? = null

    var onDragStart: (() -> Unit)? = null
    /** dy：已位移像素；progress：0~1，已换算成"离切间有多近"。 */
    var onDrag: ((Float, Float) -> Unit)? = null
    /** dy：松手时位移；vy：竖向速度 px/s（向上为负）。 */
    var onDragEnd: ((Float, Int) -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null

    var dragEnabled = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = 0f
    private var lastDy = 0f

    private val tracker = VelocityTracker.obtain()

    /** 调用方用来把 progress 换算成阈值（一屏高度）。 */
    var extent: () -> Float = { height.toFloat().coerceAtLeast(1f) }

    private fun reset() {
        dragging = false
        moved = 0f
        lastDy = 0f
        tracker.clear()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downX = ev.x
                downY = ev.y
                downAt = System.currentTimeMillis()
                tracker.addMovement(ev)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(ev)
                val dy = ev.y - downY
                val dx = ev.x - downX
                moved = maxOf(moved, abs(dy), abs(dx))
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    // 手指落在聊天列表里，而且列表还能往这个方向滚 → 让它滚
                    val scrollable = inScrollableArea?.invoke(downX, downY) == true
                    if (scrollable && childCanScroll?.invoke(dy.toInt()) == true) return false
                    dragging = true
                    lastDy = dy
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragStart?.invoke()
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
                downAt = System.currentTimeMillis()
                tracker.addMovement(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(ev)
                val dy = ev.y - downY
                val dx = ev.x - downX
                moved = maxOf(moved, abs(dy), abs(dx))
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) {
                    lastDy = dy
                    onDrag?.invoke(dy, (abs(dy) / extent()).coerceIn(0f, 1f))
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                tracker.addMovement(ev)
                if (dragging) {
                    tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = tracker.yVelocity.toInt().coerceIn(-maxFling, maxFling)
                    dragging = false
                    onDragEnd?.invoke(lastDy, vy)
                } else if (System.currentTimeMillis() - downAt < 320 && moved < touchSlop) {
                    onTap?.invoke(ev.x, ev.y)
                }
                tracker.clear()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 被动取消（比如被系统/上层打断）一律回弹，不当成切间
                    dragging = false
                    onDragEnd?.invoke(0f, 0)
                }
                tracker.clear()
                return true
            }
        }
        return true
    }

    /** 松手后由调用方回弹/切间时用，避免手势和动画互相踩。 */
    fun cancelDrag() {
        dragging = false
    }

    override fun onDetachedFromWindow() {
        tracker.recycle()
        super.onDetachedFromWindow()
    }

    companion object {
        /** 判定为 fling 的速度（px/s），对外暴露方便和位移阈值配平。 */
        const val FLING = 780
    }
}
