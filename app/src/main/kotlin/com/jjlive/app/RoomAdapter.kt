package com.jjlive.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class RoomAdapter(
    private val items: List<Room>,
    private val onClick: (Room, Int) -> Unit
) : RecyclerView.Adapter<RoomAdapter.VH>() {

    /**
     * 是否在封面上标注开播状态。
     * 关注列表里有未开播的主播，必须标清楚；首页 / 推荐列表本身都是在播，不必标。
     */
    var markStatus: Boolean = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val badge: TextView = v.findViewById(R.id.badge)
        val liveTag: TextView = v.findViewById(R.id.liveTag)
        val name: TextView = v.findViewById(R.id.name)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        val ctx = h.itemView.context

        h.name.text = r.userName
        h.title.text = r.roomName.ifEmpty { "（无标题）" }

        // 左上角：1V1 / 门票
        val label = when {
            r.performing -> "1V1 表演中"
            r.isPay -> "门票 ¥${r.moneyFen / 100}"
            else -> null
        }
        if (label == null) {
            h.badge.visibility = View.GONE
        } else {
            h.badge.visibility = View.VISIBLE
            h.badge.text = label
        }

        // 左下角：开播状态（仅在关注列表等需要区分的场景显示）
        val live = r.isLiving
        if (markStatus) {
            h.liveTag.visibility = View.VISIBLE
            if (live) {
                h.liveTag.text = "直播中"
                h.liveTag.setBackgroundResource(R.drawable.bg_live)
                h.cover.alpha = 1f
                h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.good))
            } else {
                h.liveTag.text = "未开播"
                h.liveTag.setBackgroundResource(R.drawable.bg_offline)
                // 未开播整张卡压暗，一眼能和正在播的区分开
                h.cover.alpha = 0.38f
                h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
            }
        } else {
            h.liveTag.visibility = View.GONE
            h.cover.alpha = 1f
            h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.good))
        }

        val extra = if (r.cats.isEmpty()) "" else " · " + r.cats.take(2).joinToString("/")
        h.meta.text = if (live) "🔥 ${r.hot}$extra" else "未开播"

        h.cover.setImageDrawable(null)
        val url = r.coverUrl()
        if (url.isNotEmpty()) {
            Thread {
                val bmp = Images.load(url)
                bmp?.let { h.cover.post { h.cover.setImageBitmap(it) } }
            }.start()
        }
        h.itemView.setOnClickListener {
            val at = h.bindingAdapterPosition.takeIf { p -> p != RecyclerView.NO_POSITION } ?: pos
            onClick(r, at)
        }
    }
}
