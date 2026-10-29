package com.jjlive.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
    private lateinit var btnFilter: TextView

    private val all = ArrayList<Room>()
    private val shown = ArrayList<Room>()
    private var category = ALL
    private var query = ""
    private var hideJunk = true
    private var filteredOut = 0
    private var cats: List<Category> = emptyList()

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
        btnFilter = findViewById(R.id.btnFilter)

        hideJunk = getSharedPreferences("jjlive", MODE_PRIVATE).getBoolean("hideJunk", true)
        renderFilterButton()
        btnFilter.setOnClickListener {
            hideJunk = !hideJunk
            getSharedPreferences("jjlive", MODE_PRIVATE).edit().putBoolean("hideJunk", hideJunk).apply()
            renderFilterButton()
            buildChips(cats)
            filter(query)
        }

        // 列数按屏幕宽度自适应：手机 2 列，平板/横屏 4~6 列，避免卡片被拉成长条
        rv.layoutManager = GridLayoutManager(this, spanCount())
        // 把当前可见列表整份带进播放页，播放页据此支持上下滑动切换直播间
        adapter = RoomAdapter(shown) { _, pos -> PlayerActivity.start(this, shown.toList(), pos) }
        rv.adapter = adapter

        swipe.setOnRefreshListener { load() }

        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { filter(search.text.toString()); true }
            else false
        }

        buildChips(emptyList())
        // 令牌有内置默认值，首次打开直接进列表；要换域名/令牌点右上角齿轮
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun spanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 170f).toInt().coerceIn(2, 6)
    }

    /**
     * 设置面板：接口域名 + 令牌（+ 弹幕地址）。
     *
     * 令牌失效时不用重装 App —— 原版每次换域名/换令牌都要重新打包，
     * 这里把三个值落到 SharedPreferences，改完立刻重新拉列表。
     */
    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etHost = v.findViewById<EditText>(R.id.etHost)
        val etToken = v.findViewById<EditText>(R.id.etToken)
        val etIm = v.findViewById<EditText>(R.id.etIm)
        val tvTip = v.findViewById<TextView>(R.id.tvTip)

        etHost.setText(store.host)
        etToken.setText(store.token)
        etIm.setText(if (store.imHost == Cfg.IM_WS) "" else store.imHost)
        tvTip.text = "令牌失效或域名被墙时改这里。弹幕地址一般不用动。"

        val dlg = AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(v)
            .setPositiveButton("保存", null)
            .setNeutralButton("测试", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val host = normalizeHost(etHost.text.toString())
            if (host == null) {
                etHost.error = "域名格式不对"
                return@setOnClickListener
            }
            val token = etToken.text.toString().trim()
            if (token.isEmpty()) {
                etToken.error = "令牌不能为空"
                return@setOnClickListener
            }
            store.host = host
            store.token = token
            store.imHost = etIm.text.toString()
            dlg.dismiss()
            Toast.makeText(this, "已保存，正在重新加载", Toast.LENGTH_SHORT).show()
            load()
        }

        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val host = normalizeHost(etHost.text.toString())
            if (host == null) {
                tvTip.setTextColor(Color.parseColor("#FF7A8A"))
                tvTip.text = "域名格式不对，示例：https://utp.opuxrcm.com"
                return@setOnClickListener
            }
            tvTip.setTextColor(Color.parseColor("#8B95A7"))
            tvTip.text = "测试中…"
            scope.launch {
                val (ok, msg) = api.checkToken(host, etToken.text.toString().trim())
                tvTip.setTextColor(Color.parseColor(if (ok) "#7FD1A8" else "#FF7A8A"))
                tvTip.text = if (ok) "✓ 连接正常，可以保存" else "✗ 失败：$msg"
            }
        }

        v.findViewById<TextView>(R.id.btnReset).setOnClickListener {
            store.resetAll()
            etHost.setText(store.host)
            etToken.setText(store.token)
            etIm.setText("")
            tvTip.setTextColor(Color.parseColor("#8B95A7"))
            tvTip.text = "已恢复内置默认值（点保存生效）"
        }
    }

    private fun load() {
        swipe.isRefreshing = true
        scope.launch {
            val cats = withContext(Dispatchers.IO) {
                runCatching { Parser.categories(api.call("/home/recommend/cls")) }.getOrDefault(emptyList())
            }
            this@MainActivity.cats = cats
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
            if (all.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "没有获取到房间\n令牌可能已失效，点右上角 ⚙ 更新域名 / 令牌"
            } else empty.visibility = View.GONE
        }
    }

    // ─────────────────────── 分类标签 ───────────────────────

    private fun buildChips(cats: List<Category>) {
        chips.removeAllViews()
        addChip(ALL)
        cats.map { it.name }.distinct()
            .filter { !(hideJunk && it == "游戏") }   // 过滤开启时游戏分类恒为空，干脆不显示
            .forEach { addChip(it) }

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

    private fun renderFilterButton() {
        btnFilter.text = if (hideJunk) "已过滤广告" else "未过滤"
        btnFilter.setTextColor(
            if (hideJunk) Color.parseColor("#7FD1A8") else Color.parseColor("#8B95A7")
        )
    }

    private fun filter(q: String) {
        query = q
        val key = q.trim().lowercase()

        // 广告 / 博彩 / 游戏房：默认过滤掉（anchorType=1 全是博彩推广房，名称关键字兜底）
        val base = if (hideJunk) all.filter { !it.isAd && !it.isGame } else all
        filteredOut = all.size - base.size

        shown.clear()
        shown.addAll(base.filter {
            (category == ALL || it.cats.contains(category)) &&
                    (key.isEmpty() || it.userName.lowercase().contains(key) ||
                            it.roomName.lowercase().contains(key) || it.roomId.contains(key))
        }.sortedByDescending { it.hot })
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(0)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty() && all.isNotEmpty()) empty.text = "该分类下暂无主播"

        meta.text = buildString {
            append("共 ${shown.size} 个在线房间")
            if (filteredOut > 0) append(" · 已过滤 $filteredOut 个广告/游戏")
        }
    }

    companion object {
        private const val ALL = "全部"
    }
}
