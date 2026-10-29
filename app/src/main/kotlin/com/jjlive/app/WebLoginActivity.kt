package com.jjlive.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网页一键登录。
 *
 * 直接加载 [LOGIN_URL]，登录后该网页每次请求接口都会带上 `authorization` 头，
 * 我们只要把这个头里的值抠下来写进 [TokenStore] 即可（和 App 自己的请求方式完全一致）。
 *
 * 双保险抓取令牌：
 *  1. [WebViewClient.shouldInterceptRequest] —— 在协议层扫描所有出栈请求的
 *     `authorization` 头（含 fetch / XHR / 资源加载），最稳妥；
 *  2. 注入 JS 包裹 `fetch` / `XMLHttpRequest.setRequestHeader`，并把捕获结果
 *     通过 [JsBridge] 回传，作为补充（应对个别不出现在请求头里的写法）。
 *
 * 关键：登录页加载瞬间就会带上「游客令牌」，不能直接用。每抓到一个候选令牌，
 * 都拿去 /user/info 校验是否为真实账号（[Account.isGuest]）；只有通过校验的
 * 真实令牌才会落盘，1.2 秒后自动返回。回到设置页会同步显示新令牌。
 */
class WebLoginActivity : AppCompatActivity() {

    companion object {
        /** 网页登录入口（你之前在 MuMu Via 里用的就是这个地址）。 */
        const val LOGIN_URL = "https://fvx.lkzlwj4.xyz?cc=12"
        private const val JS_BRIDGE = "JJBridge"
    }

    private lateinit var web: WebView
    private lateinit var tvStatus: TextView
    private lateinit var store: TokenStore
    private val api by lazy { Api(store) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captured = false
    /** 最近一次已校验过的候选令牌，避免对同一令牌反复发起校验请求。 */
    private var lastChecked: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)

        store = TokenStore(this)
        web = findViewById(R.id.webLogin)
        tvStatus = findViewById(R.id.tvLoginStatus)
        findViewById<View>(R.id.btnLoginClose).setOnClickListener { finish() }

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // 让网页识别为移动端，登录流程更顺
        s.userAgentString = (s.userAgentString ?: "") + " JJLiveLogin/1.0"
        s.cacheMode = WebSettings.LOAD_DEFAULT

        web.addJavascriptInterface(JsBridge(), JS_BRIDGE)
        web.webViewClient = LoginWebViewClient()

        status("正在加载登录页…", R.color.text_secondary)
        web.loadUrl(LOGIN_URL)
    }

    private fun status(text: String, colorRes: Int) {
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        tvStatus.text = text
    }

    /** 规整原始令牌：去引号、去 Bearer 前缀。 */
    private fun normalize(raw: String): String {
        var t = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (t.startsWith("Bearer ", true)) t = t.substring(7).trim()
        return t
    }

    /**
     * 抓到候选令牌：不直接落盘，而是拿去 /user/info 校验是否为「真实账号」。
     * 登录页加载瞬间就会带上游客令牌，必须排除；只有校验到真实账号才保存并退出。
     */
    private fun onToken(raw: String) {
        val t = normalize(raw)
        if (t.isEmpty() || t.length < 16 || captured) return
        if (t == lastChecked) return
        lastChecked = t
        status("捕获到令牌（长度 ${t.length}），正在校验是否为真实账号…", R.color.text_secondary)
        scope.launch {
            val acct = runCatching {
                withContext(Dispatchers.IO) { api.accountInfo(store.host, t) }
            }.getOrNull()
            if (captured) return@launch
            if (acct != null && !acct.isGuest) {
                commit(t, acct)
            } else {
                // 游客 / 无效令牌：继续监听，等用户在网页里登录后出现的真实令牌
                status(
                    "捕获到的是游客或无效令牌，请在该网页里输入账号密码登录；登录成功后本页会自动捕获真实令牌并退出",
                    R.color.warn
                )
            }
        }
    }

    /** 校验通过：落盘 → 提示 → 延时返回。 */
    private fun commit(t: String, acct: Account) {
        captured = true
        store.token = t
        status("已保存「${acct.userName}」的登录令牌（关注 ${acct.attenCnt}），即将返回", R.color.good)
        Toast.makeText(this, "登录成功：${acct.userName}", Toast.LENGTH_LONG).show()
        web.postDelayed({ finish() }, 1200)
    }

    /** JS 桥：把网页里捕获到的令牌回传给 Kotlin。 */
    private inner class JsBridge {
        @JavascriptInterface
        fun onToken(token: String) {
            // 必须显式指定外层 Activity 的方法，否则会递归调用 JsBridge.onToken 自身
            runOnUiThread { this@WebLoginActivity.onToken(token) }
        }
    }

    private inner class LoginWebViewClient : WebViewClient() {

        /** 协议层兜底：扫出栈请求的 authorization 头。
         *  getRequestHeaders() 是 API 24+，用反射取，避免 API 23 上静态引用导致 VerifyError / NoSuchMethodError。 */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val headers = request?.let { req ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    WebResourceRequest::class.java.getMethod("getRequestHeaders")
                        .invoke(req) as? Map<String, String>
                } catch (_: Throwable) { null }
            }
            headers?.forEach { (k, v) ->
                if (k.equals("authorization", true)) {
                    val tok = v?.trim().orEmpty()
                    if (tok.isNotEmpty()) runOnUiThread { this@WebLoginActivity.onToken(tok) }
                }
            }
            return null // 返回 null = 让 WebView 照常处理该请求
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            injectCaptureJs()
            if (!captured) {
                status("登录页已加载，请在网页里登录你的账号", R.color.text_secondary)
            }
        }
    }

    /** 注入 JS：包裹 fetch / XHR，并周期性扫描 localStorage。 */
    @SuppressLint("JavascriptInterface")
    private fun injectCaptureJs() {
        val js = """
        (function () {
          if (window.__jjcap) return;
          window.__jjcap = true;
          function send(t) {
            if (t && t.length >= 16) { try { $JS_BRIDGE.onToken(String(t)); } catch (e) {} }
          }
          function look() {
            try {
              for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                var v = localStorage.getItem(k);
                if (/token|auth|access/i.test(k || '') && v && v.length >= 16) send(v);
              }
            } catch (e) {}
          }
          var _fetch = window.fetch;
          window.fetch = function () {
            var a = arguments[1] || {};
            var h = a.headers || {};
            var t = h.authorization || h.Authorization || (h.get ? h.get('authorization') : null);
            if (t) send(t);
            return _fetch.apply(this, arguments);
          };
          var _set = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
            if (/authorization/i.test(k)) send(v);
            return _set.apply(this, arguments);
          };
          look();
          setInterval(look, 1500);
        })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        scope.cancel()
        web.destroy()
        super.onDestroy()
    }
}
