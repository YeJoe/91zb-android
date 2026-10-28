package com.jjlive.app

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 聊天区的一条消息。 */
data class ChatMsg(val nick: String, val body: String, val system: Boolean = false) {
    /** 系统提示（进房、礼物、关注等）用灰绿色，普通聊天昵称按 uid 取个稳定颜色。 */
    val color: Int
        get() = if (system) Color.parseColor("#7FD1A8") else NICK_COLORS[(nick.hashCode() and 0x7FFFFFFF) % NICK_COLORS.size]

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

class ChatAdapter(private val items: List<ChatMsg>) : RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val text = if (m.system || m.nick.isEmpty()) m.body else "${m.nick}：${m.body}"
        val span = SpannableString(text)
        if (!m.system && m.nick.isNotEmpty()) {
            span.setSpan(
                ForegroundColorSpan(m.color), 0, m.nick.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            span.setSpan(
                ForegroundColorSpan(m.color), 0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        holder.tv.text = span
    }
}
