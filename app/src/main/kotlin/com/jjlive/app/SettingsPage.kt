package com.jjlive.app

import android.os.Build
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 设置页（底部第三栏）。
 *
 * 全部落到 SharedPreferences，改完即刻生效：
 *  · 域名 / 令牌 / 弹幕地址 —— 换线路、令牌失效时不用重装；
 *  · 默认形态 / 优先清晰度 / 过滤广告房 —— 影响播放页与首页。
 */
class SettingsPage(
    private val act: AppCompatActivity,
    root: android.view.View,
    private val store: TokenStore,
    private val api: Api,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
    private val onFilterChanged: () -> Unit
) {

    private val etHost: EditText = root.findViewById(R.id.etHost)
    private val etToken: EditText = root.findViewById(R.id.etToken)
    private val etIm: EditText = root.findViewById(R.id.etIm)
    private val tvTip: TextView = root.findViewById(R.id.tvTip)
    private val btnTest: TextView = root.findViewById(R.id.btnTest)
    private val btnSave: TextView = root.findViewById(R.id.btnSave)
    private val btnReset: TextView = root.findViewById(R.id.btnReset)
    private val swFull: MaterialSwitch = root.findViewById(R.id.swFull)
    private val swFilter: MaterialSwitch = root.findViewById(R.id.swFilter)
    private val qAuto: TextView = root.findViewById(R.id.qAuto)
    private val q720: TextView = root.findViewById(R.id.q720)
    private val q360: TextView = root.findViewById(R.id.q360)
    private val tvVersion: TextView = root.findViewById(R.id.tvVersion)

    private var filled = false

    init {
        btnSave.setOnClickListener { save() }
        btnTest.setOnClickListener { test() }
        btnReset.setOnClickListener { reset() }

        qAuto.setOnClickListener { pickQuality(0) }
        q720.setOnClickListener { pickQuality(1) }
        q360.setOnClickListener { pickQuality(2) }

        tvVersion.text = versionText()
    }

    fun onShown() {
        if (!filled) fill()
        renderQuality()
    }

    /** 别处（首页过滤按钮）改了偏好后回填开关状态。 */
    fun syncSwitches() {
        bindSwitches()
    }

    private fun fill() {
        filled = true
        etHost.setText(store.host)
        etToken.setText(store.token)
        etIm.setText(if (store.imHost == Cfg.IM_WS) "" else store.imHost)
        tvTip.setTextColor(color(R.color.text_secondary))
        tvTip.text = "令牌失效或域名被墙时改这里，保存后立即重连。"
        bindSwitches()
        renderQuality()
    }

    private fun bindSwitches() {
        // 回填时先摘掉监听，避免"设值 → 写回"的无谓写入
        swFilter.setOnCheckedChangeListener(null)
        swFull.setOnCheckedChangeListener(null)
        swFull.isChecked = store.defaultFull
        swFilter.isChecked = store.hideJunk
        swFull.setOnCheckedChangeListener { _, on -> store.defaultFull = on }
        swFilter.setOnCheckedChangeListener { _, on ->
            store.hideJunk = on
            onFilterChanged()
        }
    }

    private fun pickQuality(q: Int) {
        store.quality = q
        renderQuality()
    }

    private fun renderQuality() {
        val cur = store.quality
        val accent = color(R.color.accent)
        val idle = color(R.color.text_secondary)
        listOf(qAuto to 0, q720 to 1, q360 to 2).forEach { (v, q) ->
            val on = cur == q
            v.setBackgroundResource(if (on) R.drawable.bg_pill_accent else R.drawable.bg_field)
            v.setTextColor(if (on) accent else idle)
        }
    }

    private fun save() {
        val host = normalizeHost(etHost.text.toString())
        if (host == null) {
            etHost.error = "域名格式不对"
            return
        }
        val token = etToken.text.toString().trim()
        if (token.isEmpty()) {
            etToken.error = "令牌不能为空"
            return
        }
        store.host = host
        store.token = token
        store.imHost = etIm.text.toString()
        filled = false
        tip("已保存，正在重新加载", R.color.good)
        Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show()
        onSaved()
    }

    private fun test() {
        val host = normalizeHost(etHost.text.toString())
        if (host == null) {
            tip("域名格式不对，示例：https://utp.opuxrcm.com", R.color.warn)
            return
        }
        val token = etToken.text.toString().trim()
        if (token.isEmpty()) {
            tip("令牌不能为空", R.color.warn)
            return
        }
        tip("测试中…", R.color.text_secondary)
        scope.launch {
            val (ok, msg) = api.checkToken(host, token)
            if (!ok) {
                tip("✗ 失败：$msg", R.color.warn)
                return@launch
            }
            // 再问一下账号是谁，方便确认换的是哪个号
            val acct = runCatching { api.accountInfo(host, token) }.getOrNull()
            tip(
                if (acct != null && acct.userName.isNotBlank()) {
                    "✓ ${acct.userName} · 关注 ${acct.attenCnt} · 粉丝 ${acct.fansCnt}"
                } else {
                    "✓ 连接正常，可以保存"
                },
                R.color.good
            )
        }
    }

    private fun reset() {
        store.resetAll()
        fill()
        tip("已恢复内置默认值（点保存生效）", R.color.text_secondary)
    }

    private fun tip(text: String, colorRes: Int) {
        tvTip.setTextColor(color(colorRes))
        tvTip.text = text
    }

    private fun color(res: Int) = ContextCompat.getColor(act, res)

    private fun versionText(): String = runCatching {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "版本 ${pi.versionName} ($code)"
    }.getOrDefault("版本未知")
}
