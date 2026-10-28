package com.jjlive.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var store: TokenStore
    private lateinit var api: Api
    private lateinit var adapter: RoomAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var meta: TextView
    private lateinit var chips: ChipGroup
    private lateinit var search: EditText

    private val all = ArrayList<Room>()
    private val shown = ArrayList<Room>()
    private var category = ALL
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = TokenStore(this)
        api = Api(store)

        swipe = findViewById(R.id.swipe)
        rv = findViewById(R.id.rv)
        empty = findViewById(R.id.empty)
        meta = findViewById(R.id.meta)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)

        // 列数按屏幕宽度自适应：手机 2 列，平板/横屏 4~6 列，避免卡片被拉成长条
        rv.layoutManager = GridLayoutManager(this, spanCount())
        adapter = RoomAdapter(shown) { PlayerActivity.start(this, it) }
        rv.adapter = adapter

        swipe.setOnRefreshListener { load() }

        findViewById<FloatingActionButton>(R.id.fabToken).setOnClickListener { askToken() }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { filter(search.text.toString()); true }
            else false
        }

        buildChips(emptyList())
        if (store.token == null) askToken() else load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun spanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 170f).toInt().coerceIn(2, 6)
    }

    private fun askToken() {
        val input = EditText(this).apply {
            setText(store.token ?: "")
            hint = "粘贴 authorization 令牌"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("令牌（authorization）")
            .setMessage("从 App / 网页登录后的请求头 authorization 复制。\n令牌失效后在此更新即可。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                store.token = t
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun load() {
        if (store.token == null) { swipe.isRefreshing = false; return }
        swipe.isRefreshing = true
        scope.launch {
            val cats = withContext(Dispatchers.IO) {
                runCatching { Parser.categories(api.call("/home/recommend/cls")) }.getOrDefault(emptyList())
            }
            val rooms = withContext(Dispatchers.IO) {
                val out = LinkedHashMap<String, Room>()
                for (c in cats) {
                    for (p in 1..40) {
                        val r = runCatching {
                            api.call("/home/default/cls/query", mapOf("type" to c.fixedType.toString(), "pageNo" to p.toString(), "pageSize" to "20"))
                        }.getOrNull() ?: break
                        val list = Parser.rooms(r)
                        if (list.isEmpty()) break
                        for (room in list) {
                            val old = out[room.roomId]
                            out[room.roomId] = if (old == null) room.copy(cats = listOf(c.name))
                            else old.copy(cats = old.cats + c.name)
                        }
                        if (list.size < 20) break
                    }
                }
                out.values.toList()
            }
            swipe.isRefreshing = false
            all.clear()
            all.addAll(rooms)
            buildChips(cats)
            filter(query)
            meta.text = "共 ${all.size} 个在线房间 · ${cats.size} 个分类"
            if (all.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "没有获取到房间\n请检查令牌是否有效（右下角按钮更新）"
            } else empty.visibility = View.GONE
        }
    }

    // ─────────────────────── 分类标签 ───────────────────────

    private fun buildChips(cats: List<Category>) {
        chips.removeAllViews()
        addChip(ALL)
        cats.map { it.name }.distinct().forEach { addChip(it) }

        // 当前分类已经不在了（例如刷新后分类变动）就回到“全部”
        if (category != ALL && chips.findViewWithTag<Chip>(category) == null) category = ALL
        chips.findViewWithTag<Chip>(category)?.isChecked = true
    }

    private fun addChip(name: String) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val chip = Chip(this).apply {
            text = name
            tag = name
            isCheckable = true
            isChecked = name == category
            isClickable = true
            isFocusable = true
            chipMinHeight = 30f * resources.displayMetrics.density
            chipStrokeWidth = 0f
            chipStartPadding = 4f
            chipEndPadding = 4f
            textSize = 12.5f
            chipBackgroundColor = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(Color.parseColor("#FF5C8A"), Color.parseColor("#1A2130"))
            )
            setTextColor(
                ColorStateList(
                    arrayOf(checked, intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#AEB8C8"))
                )
            )
            setOnClickListener {
                category = name
                filter(query)
            }
        }
        chips.addView(chip)
    }

    private fun filter(q: String) {
        query = q
        val key = q.trim().lowercase()
        shown.clear()
        shown.addAll(all.filter {
            (category == ALL || it.cats.contains(category)) &&
                    (key.isEmpty() || it.userName.lowercase().contains(key) ||
                            it.roomName.lowercase().contains(key) || it.roomId.contains(key))
        }.sortedByDescending { it.hot })
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(0)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty() && all.isNotEmpty()) empty.text = "该分类下暂无主播"
    }

    companion object {
        private const val ALL = "全部"
    }
}
