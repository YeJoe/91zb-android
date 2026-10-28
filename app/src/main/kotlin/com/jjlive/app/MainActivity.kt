package com.jjlive.app

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

    private val all = ArrayList<Room>()
    private val shown = ArrayList<Room>()
    private var category = "全部"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = TokenStore(this)
        api = Api(store)

        swipe = findViewById(R.id.swipe)
        rv = findViewById(R.id.rv)
        empty = findViewById(R.id.empty)
        meta = findViewById(R.id.meta)

        rv.layoutManager = GridLayoutManager(this, 2)
        adapter = RoomAdapter(shown) { PlayerActivity.start(this, it) }
        rv.adapter = adapter

        swipe.setOnRefreshListener { load() }

        findViewById<FloatingActionButton>(R.id.fabToken).setOnClickListener { askToken() }
        findViewById<EditText>(R.id.search).apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { filter(text.toString()); true }
                else false
            }
        }

        if (store.token == null) askToken() else load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            filter("")
            meta.text = "共 ${all.size} 个在线房间 · ${cats.size} 个类别"
            if (all.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "没有获取到房间\n请检查令牌是否有效（右下角按钮更新）"
            } else empty.visibility = View.GONE
        }
    }

    private fun filter(q: String) {
        val key = q.trim().lowercase()
        shown.clear()
        shown.addAll(all.filter {
            (category == "全部" || it.cats.contains(category)) &&
                    (key.isEmpty() || it.userName.lowercase().contains(key) ||
                            it.roomName.lowercase().contains(key) || it.roomId.contains(key))
        }.sortedByDescending { it.hot })
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }
}
