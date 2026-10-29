package com.jjlive.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/**
 * 主界面：底部三个分类。
 *
 *  · 首页 —— 分类标签 + 房间网格，支持搜索、过滤广告房；
 *  · 关注 —— 拉真实关注列表（/home/user/attention/list），空态给推荐并支持一键关注；
 *  · 设置 —— 域名 / 令牌 / 弹幕地址 + 播放偏好。
 *
 * 三个页面用 include 引入、切 tab 只改 visibility：不重建列表，回来还在原滚动位置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: TokenStore
    private lateinit var api: Api
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 外壳
    private lateinit var pages: View
    private lateinit var navBar: View
    private lateinit var pageHome: View
    private lateinit var pageFollow: View
    private lateinit var pageSettings: View
    private lateinit var followPage: FollowPage
    private lateinit var settingsPage: SettingsPage
    private var tab = TAB_HOME

    // 首页
    private lateinit var adapter: RoomAdapter
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
    private var cats: List<Category> = emptyList()
    private var filterHits = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = TokenStore(this)
        api = Api(store)

        pages = findViewById(R.id.pages)
        navBar = findViewById(R.id.navBar)
        pageHome = findViewById(R.id.pageHome)
        pageFollow = findViewById(R.id.pageFollow)
        pageSettings = findViewById(R.id.pageSettings)

        setupHome()
        followPage = FollowPage(this, pageFollow,
            api = api,
            scope = scope,
            onOpen = { list, idx -> PlayerActivity.start(this, list, idx) },
            onGoHome = { selectTab(TAB_HOME) })
        settingsPage = SettingsPage(
            act = this,
            root = pageSettings,
            store = store,
            api = api,
            scope = scope,
            onSaved = { load(refreshAll = true) },   // 换了域名/令牌：首页重拉
            onFilterChanged = { onFilterPrefChanged() }
        )

        selectTab(TAB_HOME, animate = false)
        applyNavInsets()
        load(refreshAll = true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────── 底部导航 ───────────

    private fun setupHome() {
        swipe = pageHome.findViewById(R.id.swipe)
        rv = pageHome.findViewById(R.id.rv)
        empty = pageHome.findViewById(R.id.empty)
        meta = pageHome.findViewById(R.id.meta)
        chips = pageHome.findViewById(R.id.chips)
        search = pageHome.findViewById(R.id.search)
        btnFilter = pageHome.findViewById(R.id.btnFilter)

        rv.layoutManager = GridLayoutManager(this, spanCount())
        adapter = RoomAdapter(shown) { _, pos -> PlayerActivity.start(this, shown.toList(), pos) }
        rv.adapter = adapter

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        swipe.setOnRefreshListener { load() }

        pageHome.findViewById<View>(R.id.btnSettings).setOnClickListener { selectTab(TAB_SETTINGS) }
        btnFilter.setOnClickListener {
            store.hideJunk = !store.hideJunk
            settingsPage.syncSwitches()
            renderFilterButton()
            buildChips(cats)
            filter(query)
        }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { filter(search.text.toString()); true } else false
        }

        findViewById<View>(R.id.navHome).setOnClickListener { selectTab(TAB_HOME) }
        findViewById<View>(R.id.navFollow).setOnClickListener { selectTab(TAB_FOLLOW) }
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab(TAB_SETTINGS) }

        renderFilterButton()
        buildChips(emptyList())
    }

    private fun selectTab(which: Int, animate: Boolean = true) {
        tab = which
        val list = arrayOf(pageHome, pageFollow, pageSettings)
        list.forEachIndexed { i, v ->
            v.animate().cancel()
            if (i == which) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.translationY = dp(10).toFloat()
                    v.animate().alpha(1f).translationY(0f).setDuration(170).start()
                } else {
                    v.alpha = 1f
                    v.translationY = 0f
                }
            } else {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationY = 0f
            }
        }
        updateNavColors()
        when (which) {
            TAB_FOLLOW -> followPage.onShown()
            TAB_SETTINGS -> settingsPage.onShown()
        }
    }

    private fun updateNavColors() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val idle = ContextCompat.getColor(this, R.color.nav_idle)
        val tabs = arrayOf(
            Triple(R.id.navHomeIcon, R.id.navHomeText, TAB_HOME),
            Triple(R.id.navFollowIcon, R.id.navFollowText, TAB_FOLLOW),
            Triple(R.id.navSettingsIcon, R.id.navSettingsText, TAB_SETTINGS)
        )
        for ((iconId, textId, idx) in tabs) {
            val on = idx == tab
            val c = if (on) accent else idle
            findViewById<ImageView>(iconId).imageTintList = ColorStateList.valueOf(c)
            findViewById<TextView>(textId).setTextColor(c)
        }
    }

    /** 底部导航避开系统手势条。 */
    private fun applyNavInsets() {
        val baseH = dp(56)
        ViewCompat.setOnApplyWindowInsetsListener(navBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            v.layoutParams = v.layoutParams.apply { height = baseH + bottom }
            insets
        }
        ViewCompat.requestApplyInsets(pages)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 170f).toInt().coerceIn(2, 6)
    }

    // ─────────── 首页数据 ───────────

    /**
     * @param refreshAll true 表示连分类一起重新拉（改域名 / 令牌后必须），
     *                   false 表示普通刷新（沿用已缓存的分类）。
     */
    private fun load(refreshAll: Boolean = false) {
        swipe.isRefreshing = true
        scope.launch {
            if (refreshAll || cats.isEmpty()) {
                val c = withContext(Dispatchers.IO) {
                    runCatching { Parser.categories(api.call("/home/recommend/cls")) }
                        .getOrDefault(emptyList())
                }
                if (c.isNotEmpty()) cats = c
                buildChips(cats)
            }
            val rooms = withContext(Dispatchers.IO) {
                val out = LinkedHashMap<String, Room>()
                for (c in cats) {
                    for (p in 1..40) {
                        val r = runCatching {
                            api.call(
                                "/home/default/cls/query",
                                mapOf(
                                    "type" to c.fixedType.toString(),
                                    "pageNo" to p.toString(),
                                    "pageSize" to "20"
                                )
                            )
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
            filter(query)
            if (all.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "没有获取到房间\n令牌可能已失效，去「设置」更新域名 / 令牌"
            } else empty.visibility = View.GONE
        }
    }

    // ─────────── 分类标签 ───────────

    private fun buildChips(cats: List<Category>) {
        chips.removeAllViews()
        addChip(ALL)
        cats.map { it.name }.distinct()
            .filter { !(store.hideJunk && it == "游戏") }
            .forEach { addChip(it) }

        if (category != ALL && chips.findViewWithTag<Chip>(category) == null) category = ALL
        chips.findViewWithTag<Chip>(category)?.isChecked = true
    }

    private fun addChip(name: String) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val onAccent = ContextCompat.getColor(this, R.color.accent_ink)
        val chipBg = ContextCompat.getColor(this, R.color.surface_alt)
        val chipText = ContextCompat.getColor(this, R.color.text_secondary)

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
            chipBackgroundColor = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(accent, chipBg))
            setTextColor(ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(onAccent, chipText)))
            setOnClickListener {
                category = name
                filter(query)
            }
        }
        chips.addView(chip)
    }

    private fun renderFilterButton() {
        btnFilter.text = if (store.hideJunk) "已过滤广告" else "未过滤"
        btnFilter.setTextColor(
            ContextCompat.getColor(this, if (store.hideJunk) R.color.good else R.color.text_secondary)
        )
    }

    /** 设置页改了过滤开关后回来同步首页按钮文案。 */
    fun onFilterPrefChanged() {
        renderFilterButton()
        buildChips(cats)
        filter(query)
    }

    private fun filter(q: String) {
        query = q
        val key = q.trim().lowercase()

        val base = if (store.hideJunk) all.filter { !it.isAd && !it.isGame } else all
        filterHits = all.size - base.size

        shown.clear()
        shown.addAll(
            base.filter {
                (category == ALL || it.cats.contains(category)) &&
                        (key.isEmpty() || it.userName.lowercase().contains(key) ||
                                it.roomName.lowercase().contains(key) || it.roomId.contains(key))
            }.sortedByDescending { it.hot }
        )
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(0)

        if (shown.isEmpty()) {
            empty.visibility = View.VISIBLE
            if (all.isNotEmpty()) empty.text = "该分类下暂无主播"
        } else empty.visibility = View.GONE

        meta.text = buildString {
            append("共 ${shown.size} 个在线房间")
            if (filterHits > 0) append(" · 已过滤 $filterHits 个广告/游戏")
        }
    }

    companion object {
        private const val ALL = "全部"
        private const val TAB_HOME = 0
        private const val TAB_FOLLOW = 1
        private const val TAB_SETTINGS = 2
    }
}
