package com.jjlive.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RoomAdapter(
    private val items: List<Room>,
    private val onClick: (Room, Int) -> Unit
) : RecyclerView.Adapter<RoomAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val badge: TextView = v.findViewById(R.id.badge)
        val name: TextView = v.findViewById(R.id.name)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        h.name.text = r.userName
        h.title.text = r.roomName.ifEmpty { "（无标题）" }

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

        val extra = if (r.cats.isEmpty()) "" else " · " + r.cats.take(2).joinToString("/")
        h.meta.text = "🔥 ${r.hot}$extra"

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
