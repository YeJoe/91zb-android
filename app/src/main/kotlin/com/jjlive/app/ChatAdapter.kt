package com.jjlive.app

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 聊天区的一条消息。
 * @param level 用户等级（IM 帧的 lv），0 表示未知，不显示等级前缀。
 * @param system 本地提示（例如自己进入房间），用暗绿色斜体，和真实弹幕区分开。
 */
data class ChatMsg(
    val nick: String,
    val body: String,
    val level: Int = 0,
    val system: Boolean = false
) {
    val nickColor: Int
        get() = NICK_COLORS[(nick.hashCode() and 0x7FFFFFFF) % NICK_COLORS.size]

    companion object {
        private val NICK_COLORS = intArrayOf(
            Color.parseColor("#FF9DB6"),
            Color.parseColor("#8FD3FF"),
            Color.parseColor("#FFD479"),
            Color.parseColor("#B8A9FF"),
            Color.parseColor("#7FD1A8"),
            Color.parseColor("#FFB07C")
        )
    }
}

class ChatAdapter(private val items: MutableList<ChatMsg>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val sb = SpannableStringBuilder()

        if (m.system) {
            sb.append(m.body)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#9AE6C0")), 0, sb.length, SPAN)
            return
        }

        // 等级：Lv12（不占地方，颜色比昵称淡一档）
        if (m.level > 0) {
            val lv = "Lv${m.level}"
            val from = sb.length
            sb.append(lv)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#FF8FB0")), from, sb.length, SPAN)
            sb.setSpan(RelativeSizeSpan(0.78f), from, sb.length, SPAN)
            sb.append("  ")
        }

        // 昵称
        val nickFrom = sb.length
        sb.append(m.nick)
        sb.setSpan(ForegroundColorSpan(m.nickColor), nickFrom, sb.length, SPAN)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), nickFrom, sb.length, SPAN)

        // 正文
        sb.append("  ")
        val bodyFrom = sb.length
        sb.append(m.body)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#F2F5FA")), bodyFrom, sb.length, SPAN)

        holder.tv.text = sb
    }

    /** 追加一条并带插入动画，超出上限从头部丢弃。 */
    fun push(msg: ChatMsg) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
        while (items.size > MAX) {
            items.removeAt(0)
            notifyItemRemoved(0)
        }
    }

    fun clear() {
        val n = items.size
        if (n == 0) return
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun last(): ChatMsg? = items.lastOrNull()

    companion object {
        private const val MAX = 80
        private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}
