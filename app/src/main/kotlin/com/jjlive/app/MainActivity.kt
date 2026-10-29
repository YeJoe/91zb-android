package com.jjlive.app

import android.content.Intent
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
import android.view.MotionEvent
import kotlin.math.abs
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
    private lateinit var pageSettings: View
    private lateinit var settingsPage: SettingsPage
    private var tab = TAB_HOME
    /** 最近一次已知的令牌，用于在 onResume 时侦测「网页登录后令牌变化」。 */
    private var lastToken = ""

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
    private val followRooms = ArrayList<Room>()
    private var filterHits = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = TokenStore(this)
        api = Api(store)
        lastToken = store.token

        pages = findViewById(R.id.pages)
        navBar = findViewById(R.id.navBar)
        pageHome = findViewById(R.id.pageHome)
        pageSettings = findViewById(R.id.pageSettings)

        setupHome()
        settingsPage = SettingsPage(
            act = this,
            root = pageSettings,
            store = store,
            api = api,
            scope = scope,
            onSaved = {
                lastToken = store.token
                load(refreshAll = true)   // 换了域名/令牌：首页重拉
            },
            onFilterChanged = { onFilterPrefChanged() },
            onWebLogin = { startActivity(Intent(this, WebLoginActivity::class.java)) }
        )

        selectTab(TAB_HOME, animate = false)
        applyNavInsets()
        load(refreshAll = true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 从 WebView 登录页返回后，令牌可能被外部改写；变化时回填设置并刷新数据
        val t = store.token
        if (t != lastToken) {
            lastToken = t
            settingsPage.syncToken()
            load(refreshAll = true)
        }
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
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab(TAB_SETTINGS) }

        renderFilterButton()
        buildChips(emptyList())

        // 左右滑动切换分类（在网格区域横向滑动即可切到上一个 / 下一个分类）
        // 不用 GestureDetector.onFling：各 compileSdk 对 e1/e2 的可空性注解不一致
        // （@Nullable e1, @NonNull e2），override 签名极易在 CI 上触发 "overrides nothing"。
        // 这里直接用 OnTouchListener 按位移判断水平滑动——原本也只看位移不看速度，
        // 行为等价，且零签名风险。返回 false 不消费事件，网格仍可正常点击/垂直滚动。
        var downX = 0f
        var downY = 0f
        rv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > abs(dy) && abs(dx) > dp(60)) {
                        val order = chipOrder()
                        val idx = order.indexOf(category).coerceAtLeast(0)
                        val target = if (dx < 0) idx + 1 else idx - 1
                        if (target in order.indices) selectCategory(order[target])
                    }
                }
            }
            false
        }
    }

    private fun selectTab(which: Int, animate: Boolean = true) {
        tab = which
        val list = arrayOf(pageHome, pageSettings)
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
            TAB_SETTINGS -> settingsPage.onShown()
        }
    }

    private fun updateNavColors() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val idle = ContextCompat.getColor(this, R.color.nav_idle)
        val tabs = arrayOf(
            Triple(R.id.navHomeIcon, R.id.navHomeText, TAB_HOME),
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
            if (category == FOLLOW) loadFollow()
            if (all.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "没有获取到房间\n令牌可能已失效，去「设置」更新域名 / 令牌"
            } else empty.visibility = View.GONE
        }
    }

    // ─────────── 分类标签 ───────────

    private fun buildChips(cats: List<Category>) {
        chips.removeAllViews()
        addChip(FOLLOW)
        addChip(ALL)
        // 「关注 / 全部」由上面两个固定 chip 承载，接口若再返回同名分类要去掉，
        // 否则顶部会出现两个「关注」。
        cats.map { it.name }.distinct()
            .filter { it != FOLLOW && it != ALL }
            .filter { !(store.hideJunk && it == "游戏") }
            .forEach { addChip(it) }

        if (category != ALL && category != FOLLOW && chips.findViewWithTag<Chip>(category) == null) category = ALL
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
                selectCategory(name)
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

        if (category == FOLLOW) {
            val fb = if (store.hideJunk) followRooms.filter { !it.isAd && !it.isGame } else followRooms
            shown.clear()
            shown.addAll(
                fb.filter {
                    key.isEmpty() || it.userName.lowercase().contains(key) ||
                            it.roomName.lowercase().contains(key) || it.roomId.contains(key)
                }.sortedByDescending { it.hot }
            )
            adapter.notifyDataSetChanged()
            rv.scrollToPosition(0)
            if (shown.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = if (followRooms.isEmpty()) "正在读取关注列表…\n或你还没有关注任何人"
                else "关注列表里没有匹配的主播"
            } else empty.visibility = View.GONE
            meta.text = "我的关注 · 共 ${followRooms.size} 位"
            return
        }

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

    /** 选中某个分类（含"关注"），同步顶部 chip 高亮，并在首次进入关注时拉取关注列表。 */
    private fun selectCategory(name: String) {
        category = name
        chips.findViewWithTag<Chip>(name)?.isChecked = true
        if (name == FOLLOW && followRooms.isEmpty()) loadFollow()
        filter(query)
    }

    /** 分页拉全关注列表（关注数可能上百，只拉第一页会漏）。 */
    private fun loadFollow() {
        scope.launch {
            val following = withContext(Dispatchers.IO) {
                val out = ArrayList<Room>()
                var page = 1
                while (page <= 10) {
                    val batch = runCatching { api.attentionList(page, 30) }
                        .getOrDefault(emptyList())
                    if (batch.isEmpty()) break
                    out.addAll(batch)
                    if (batch.size < 30) break
                    page++
                }
                out
            }
            followRooms.clear()
            followRooms.addAll(following)
            if (category == FOLLOW) filter(query)
        }
    }

    /** 顶部分类的顺序（与 buildChips 一致），供左右滑动切换定位。 */
    private fun chipOrder(): List<String> {
        val names = cats.map { it.name }.distinct()
            .filter { it != FOLLOW && it != ALL }
            .filter { !(store.hideJunk && it == "游戏") }
        return listOf(FOLLOW, ALL) + names
    }

    companion object {
        private const val ALL = "全部"
        private const val FOLLOW = "关注"
        private const val TAB_HOME = 0
        private const val TAB_SETTINGS = 1
    }
}
