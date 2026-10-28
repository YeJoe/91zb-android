package com.jjlive.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RoomAdapter(
    private val items: List<Room>,
    private val onClick: (Room) -> Unit
) : RecyclerView.Adapter<RoomAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
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
        val flags = buildList {
            if (r.performing) add("1V1表演中")
            else if (r.cats.contains("1V1表演")) add("1V1")
            if (r.isPay) add("门票¥${r.moneyFen / 100}")
        }
        h.meta.text = (listOf("🔥 ${r.hot}") + flags).joinToString(" · ")

        h.cover.setImageDrawable(null)
        val url = r.coverUrl()
        if (url.isNotEmpty()) {
            Thread {
                val bmp = Images.load(url)
                bmp?.let { h.cover.post { h.cover.setImageBitmap(it) } }
            }.start()
        }
        h.itemView.setOnClickListener { onClick(r) }
    }
}
