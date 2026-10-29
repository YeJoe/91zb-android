package com.jjlive.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关注页。
 *
 * 数据来自两个真实接口：
 *  · /home/user/attention/list  —— 我的关注（结构与房间列表一致，另带 state/stateStr）
 *  · /home/user/notattention/list —— 未关注的推荐，供空态时挑选
 *
 * 关注数为 0 时接口返回空数组（不是失败）；此时页面直接展示推荐，并允许一键关注，
 * 关注成功后再静默重拉，让新人立刻出现在「我的关注」里。
 */
class FollowPage(
    private val act: AppCompatActivity,
    private val root: View,
    private val api: Api,
    private val scope: CoroutineScope,
    private val onOpen: (List<Room>, Int) -> Unit,
    private val onGoHome: () -> Unit
) {

    private val swipe: SwipeRefreshLayout = root.findViewById(R.id.swipeFollow)
    private val rv: RecyclerView = root.findViewById(R.id.rvFollow)
    private val meta: TextView = root.findViewById(R.id.followMeta)
    private val emptyView: View = root.findViewById(R.id.followEmpty)
    private val emptyText: TextView = root.findViewById(R.id.followEmptyText)

    private val adapter = FollowAdapter()
    private val busy = HashSet<String>()

    private var loading = false
    private var loaded = false
    private var lastLoadedAt = 0L

    init {
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        swipe.setColorSchemeColors(ContextCompat.getColor(act, R.color.accent))
        swipe.setOnRefreshListener { load() }
        root.findViewById<View>(R.id.btnRefreshFollow).setOnClickListener { load() }
    }

    /** 切到关注 tab 时才拉，避免启动时多打两个接口。 */
    /** 切到关注 tab 时才拉；20 秒内重复切换不重复请求。 */
    fun onShown() {
        if (!loaded) {
            load()
        } else if (System.currentTimeMillis() - lastLoadedAt > 20_000) {
            // 从播放页返回时关注状态可能已经变了，静默刷一次（不显示转圈）
            load(silent = true)
        }
    }

    fun load(silent: Boolean = false) {
        if (loading) return
        loading = true
        if (!silent) {
            swipe.isRefreshing = true
            if (adapter.rows.isEmpty()) meta.text = "正在读取关注列表…"
        }
        scope.launch {
            // 1) 账号概况（昵称 / 关注总数）
            val acct = withContext(Dispatchers.IO) {
                runCatching { api.accountInfo() }.getOrNull()
            }
            // 2) 我的关注：分页拉全（实测有 169 条，只拉第一页会漏）
            val following = withContext(Dispatchers.IO) {
                val out = ArrayList<Room>()
                var page = 1
                while (page <= MAX_PAGES) {
                    val batch = runCatching { api.attentionList(page, PAGE_SIZE) }
                        .getOrDefault(emptyList())
                    if (batch.isEmpty()) break
                    out.addAll(batch)
                    // 服务端还有更多就继续翻；不足一页说明到底了
                    if (batch.size < PAGE_SIZE) break
                    page++
                }
                out
            }
            // 3) 推荐只在「没关注任何人」时才需要
            val suggest = if (following.isEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching { api.suggestList(1, 12) }.getOrDefault(emptyList())
                }
            } else emptyList()

            loading = false
            loaded = true
            lastLoadedAt = System.currentTimeMillis()
            swipe.isRefreshing = false
            render(following, suggest, acct)
        }
    }

    private fun render(following: List<Room>, suggest: List<Room>, acct: Account?) {
        val rows = ArrayList<Row>()

        // 在播的排前面，其次按热度
        val mine = following
            .distinctBy { it.userId }
            .sortedWith(compareByDescending<Room> { it.isLiving }.thenByDescending { it.hot })
        val living = mine.count { it.isLiving }

        if (mine.isNotEmpty()) {
            rows.add(Row.Section("我的关注 · ${mine.size}"))
            mine.forEach { rows.add(Row.Item(it, following = true, group = GROUP_MINE)) }
        }

        val mineIds = mine.map { it.userId }.toSet()
        val recommendations = suggest
            .filter { it.userId.isNotBlank() && it.userId !in mineIds && !it.isAd && !it.isGame }
            .distinctBy { it.userId }

        if (recommendations.isNotEmpty()) {
            rows.add(
                Row.Section(
                    if (mine.isEmpty()) "推荐关注 · 你还没有关注任何人" else "猜你关注"
                )
            )
            recommendations.forEach { rows.add(Row.Item(it, following = false, group = GROUP_RECO)) }
        }

        adapter.rows.clear()
        adapter.rows.addAll(rows)
        adapter.notifyDataSetChanged()

        val total = acct?.attenCnt?.takeIf { it > 0 } ?: mine.size
        meta.text = when {
            mine.isEmpty() && recommendations.isEmpty() -> "没拉到数据，检查「设置」里的令牌"
            mine.isEmpty() -> "还没有关注任何人 · 从下面挑几个"
            living > 0 -> "$living 位正在直播 · 共关注 $total 位"
            else -> "已关注 $total 位 · 当前都没有开播"
        }
        // 账号名展示在顶部副标题
        acct?.userName?.takeIf { it.isNotBlank() }?.let { who ->
            meta.text = "$who · " + meta.text
        }

        val nothing = rows.isEmpty()
        emptyView.visibility = if (nothing) View.VISIBLE else View.GONE
        if (nothing) {
            emptyText.text = "关注列表是空的\n下拉重试，或去首页逛逛"
            emptyView.setOnClickListener { onGoHome() }
        }
    }

    private fun toggleFollow(item: Row.Item) {
        val uid = item.room.userId
        if (uid.isBlank() || !busy.add(uid)) return
        val to = !item.following
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { api.follow(uid, to) }.getOrDefault(false) }
            busy.remove(uid)
            if (!ok) {
                Toast.makeText(act, if (to) "关注失败，检查令牌" else "取消关注失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 先本地改，给即时反馈；随后静默重拉，让分组与服务器一致
            val i = adapter.rows.indexOf(item)
            if (i >= 0) {
                adapter.rows[i] = item.copy(following = to)
                adapter.notifyItemChanged(i)
            }
            Toast.makeText(
                act,
                if (to) "已关注 ${item.room.userName}" else "已取消关注 ${item.room.userName}",
                Toast.LENGTH_SHORT
            ).show()
            load(silent = true)
        }
    }

    private fun open(group: Int, room: Room) {
        if (!room.isLiving) {
            Toast.makeText(act, "${room.userName} 当前未开播", Toast.LENGTH_SHORT).show()
            return
        }
        val pool = adapter.rows.filterIsInstance<Row.Item>()
            .filter { it.group == group }
            .map { it.room }
            .filter { it.isLiving && it.hasStream }
        if (pool.isEmpty()) {
            Toast.makeText(act, "该主播暂无可用播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = pool.indexOfFirst { it.roomId == room.roomId }.coerceAtLeast(0)
        onOpen(pool, idx)
    }

    // ─────────── 列表 ───────────

    private sealed class Row {
        data class Section(val title: String) : Row()
        data class Item(val room: Room, val following: Boolean, val group: Int) : Row()
    }

    private inner class FollowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val rows = ArrayList<Row>()

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) TYPE_SECTION else TYPE_ITEM

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SECTION) {
                SectionVH(inf.inflate(R.layout.item_section, parent, false))
            } else ItemVH(inf.inflate(R.layout.item_follow, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Section -> (holder as SectionVH).title.text = row.title

                is Row.Item -> {
                    val h = holder as ItemVH
                    val r = row.room
                    h.name.text = r.userName
                    h.title.text = r.roomName.ifEmpty { "（无标题）" }
                    h.liveTag.visibility = if (r.isLiving) View.VISIBLE else View.GONE

                    h.meta.text = buildString {
                        if (!r.isLiving) append("未开播")
                        else if (r.hot > 0) append("🔥 ${r.hot}")
                        if (r.isPay) {
                            if (isNotEmpty()) append(" · ")
                            append("门票 ¥${r.moneyFen / 100}")
                        }
                    }.ifEmpty { "直播中" }

                    renderFollowButton(h.btnFollow, row.following)

                    h.cover.setImageDrawable(null)
                    val url = r.coverUrl()
                    if (url.isNotEmpty()) {
                        Thread {
                            val bmp = Images.load(url) ?: return@Thread
                            h.cover.post {
                                if (h.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                    h.cover.setImageBitmap(bmp)
                                }
                            }
                        }.start()
                    }

                    h.itemView.setOnClickListener {
                        val p = h.bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) open(row.group, r)
                    }
                    h.btnFollow.setOnClickListener { toggleFollow(row) }
                }
            }
        }

        private fun renderFollowButton(btn: TextView, following: Boolean) {
            val accent = ContextCompat.getColor(act, R.color.accent)
            val onAccent = ContextCompat.getColor(act, R.color.accent_ink)
            btn.text = if (following) "已关注" else "关注"
            btn.setBackgroundResource(if (following) R.drawable.bg_pill_accent else R.drawable.bg_btn_accent)
            btn.setTextColor(if (following) accent else onAccent)
        }

        inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sectionTitle)
        }

        inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.cover)
            val liveTag: TextView = v.findViewById(R.id.liveTag)
            val name: TextView = v.findViewById(R.id.name)
            val title: TextView = v.findViewById(R.id.title)
            val meta: TextView = v.findViewById(R.id.meta)
            val btnFollow: TextView = v.findViewById(R.id.btnFollow)
        }
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_ITEM = 1
        private const val GROUP_MINE = 0
        private const val GROUP_RECO = 1

        /** 每页条数。 */
        private const val PAGE_SIZE = 30

        /** 最多翻多少页（30 × 10 = 300 条，足够覆盖绝大多数账号）。 */
        private const val MAX_PAGES = 10
    }
}
