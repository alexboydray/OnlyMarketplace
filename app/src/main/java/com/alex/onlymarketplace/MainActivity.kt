package com.alex.onlymarketplace

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var debugLog: TextView
    private val logLines = ArrayDeque<String>()
    private var mobileUa = ""

    /**
     * Pick the browser identity for a URL: desktop for login/checkpoint pages while logged out,
     * mobile everywhere else. Returns true if it changed, so the caller can reload with it.
     */
    private fun applyUaFor(uri: Uri): Boolean {
        val want = if (Marketplace.wantsDesktop(uri)) Marketplace.DESKTOP_UA else mobileUa
        if (webView.settings.userAgentString == want) return false
        webView.settings.userAgentString = want
        note(if (want == mobileUa) "UA → mobile" else "UA → desktop")
        return true
    }

    /** Debug strip at the bottom of the screen; keeps the last few events. Tap it to clear. */
    private fun note(msg: String) {
        if (!Marketplace.DEBUG_TOASTS) return
        logLines.addLast(msg.take(1200))
        while (logLines.size > 8) logLines.removeFirst()
        debugLog.text = logLines.joinToString("\n")
    }

    // Pending callback for a file picker opened from the page (listing photos).
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(webView)
            setColorSchemeColors(0xFF1877F2.toInt())
            setDistanceToTriggerSync(300) // needs a deliberate pull, not a flick
            setOnRefreshListener { webView.reload() }
        }
        debugLog = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setPadding(24, 12, 24, 12)
            visibility = if (Marketplace.DEBUG_TOASTS) View.VISIBLE else View.GONE
            setOnClickListener { logLines.clear(); text = "" }
        }
        val root = FrameLayout(this).apply {
            addView(refresh, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(debugLog, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        // Android 15 draws edge-to-edge; keep the page out from under the status/nav bars.
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false) // target=_blank links open in this same view
            // Facebook refuses bare WebViews ("unsupported browser"). Strip the WebView markers
            // from the default UA so it looks like regular Chrome on Android.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s"""), "")
            mobileUa = userAgentString
        }

        // WebViews send "X-Requested-With: <package>" on every request, which is how Facebook
        // spots in-app browsers and forces the "log in via the Facebook app" page. Drop it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }

        // Client hints (Sec-CH-UA) also announce "Android WebView" regardless of the UA string.
        // Present the same brands Chrome does.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val major = Regex("""Chrome/(\d+)""").find(webView.settings.userAgentString)?.groupValues?.get(1) ?: "131"
            val brands = listOf("Chromium", "Google Chrome", "Not_A Brand").map { b ->
                val v = if (b == "Not_A Brand") "24" else major
                UserAgentMetadata.BrandVersion.Builder().setBrand(b).setMajorVersion(v).setFullVersion("$v.0.0.0").build()
            }
            val meta = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setPlatform("Android")
                .setMobile(true)
                .build()
            WebSettingsCompat.setUserAgentMetadata(webView.settings, meta)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = MarketplaceClient()
        webView.webChromeClient = FileChooserChromeClient()

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        if (savedInstanceState == null) {
            webView.visibility = View.INVISIBLE // uncovered in onPageFinished once the bar is hidden
            webView.postDelayed({ webView.visibility = View.VISIBLE }, 6000)
            webView.loadUrl(Marketplace.HOME)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush() // keep the login session on disk
        webView.destroy()
        super.onDestroy()
    }

    /** Enforces the Marketplace-only rule for every navigation, including Facebook's in-page (pushState) ones. */
    private inner class MarketplaceClient : WebViewClient() {

        private var lastBounce = ""
        private var lastBounceAt = 0L
        private val recentBounces = ArrayDeque<Long>()

        /** Send the view back to Marketplace. Posted so it never collides with the navigation that triggered it. */
        private fun bounce(view: WebView, from: String, target: String = Marketplace.HOME) {
            val now = System.currentTimeMillis()
            if (from == lastBounce && now - lastBounceAt < 1000) return // same navigation firing twice
            lastBounce = from
            lastBounceAt = now

            // Loop guard: Marketplace itself keeps redirecting to "/" → the session is dead on
            // Facebook's side but the cookie is still here. Forget it and go to the login form.
            recentBounces.addLast(now)
            while (recentBounces.size > 0 && now - recentBounces.first() > 15000) recentBounces.removeFirst()
            if (target == Marketplace.HOME && recentBounces.size >= 4 && Uri.parse(from).path.orEmpty().trimEnd('/').isEmpty()) {
                recentBounces.clear()
                Marketplace.forgetSession()
                Toast.makeText(this@MainActivity, "Facebook session expired — please log in again", Toast.LENGTH_LONG).show()
                note("LOOP → forgetting session, going to login")
                view.visibility = View.VISIBLE
                applyUaFor(Uri.parse(Marketplace.LOGIN))
                view.loadUrl(Marketplace.LOGIN)
                return
            }
            note(if (target == Marketplace.HOME) "BLOCK ${from.take(90)}" else "→LOGIN from ${from.take(80)}")
            if (target == Marketplace.HOME) {
                // Always-on, so a wrongly blocked page is easy to report (it shows the path).
                val u = Uri.parse(from)
                Toast.makeText(this@MainActivity, "Blocked: ${u.host}${u.path}", Toast.LENGTH_SHORT).show()
            }
            // Cover the view so the blocked page never flashes; onPageFinished uncovers it.
            view.visibility = View.INVISIBLE
            view.postDelayed({ view.visibility = View.VISIBLE }, 4000) // safety net if the load stalls
            view.post { view.stopLoading(); applyUaFor(Uri.parse(target)); view.loadUrl(target) }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            note("START ${url.take(100)}")
            // Belt and braces: form posts and server redirects can skip shouldOverrideUrlLoading.
            val uri = Uri.parse(url)
            if (!Marketplace.isFacebookHost(uri) || Marketplace.loginRedirect(uri) != null) return
            if (!Marketplace.isAllowed(uri)) bounce(view, url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                // fb:// or intent:// links try to open the Facebook app. We don't want that;
                // if it was a login attempt, show the web login page instead.
                if (!Marketplace.isLoggedIn()) bounce(view, url.toString(), Marketplace.LOGIN)
                else note("Ignored app link: $url")
                return true
            }
            if (!Marketplace.isFacebookHost(url)) {
                // Off-site link (e.g. a seller's external website): hand it to the system browser.
                Toast.makeText(this@MainActivity, "Opening ${url.host} outside the app", Toast.LENGTH_SHORT).show()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
            Marketplace.loginRedirect(url)?.let { bounce(view, url.toString(), it); return true }
            if (Marketplace.isAllowed(url)) {
                // Right identity for this page? If it changed, load it ourselves so the new one applies.
                if (applyUaFor(url)) { view.loadUrl(url.toString()); return true }
                return false // let the WebView load it
            }
            bounce(view, url.toString()) // anything else bounces back to Marketplace
            return true
        }

        // Facebook is a single-page app: most clicks change the URL with pushState, which never
        // reaches shouldOverrideUrlLoading. This hook fires for those, so we re-check here too.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val uri = Uri.parse(url)
            if (!Marketplace.isFacebookHost(uri)) return
            Marketplace.loginRedirect(uri)?.let { bounce(view, url, it); return }
            if (Marketplace.isAllowed(uri)) return
            // In-page address changes can be transient (the search overlay briefly sets "/").
            // Cover the view, give it a beat, then decide from what's actually on screen.
            view.visibility = View.INVISIBLE
            view.postDelayed({
                val now = Uri.parse(view.url ?: "")
                if (Marketplace.isAllowed(now)) { view.visibility = View.VISIBLE; return@postDelayed }
                view.evaluateJavascript("(function(){var a=document.activeElement;return !!(a&&a.tagName==='INPUT')})()") { r ->
                    if (r == "true") { note("search UI at ${now.path}, allowed"); view.visibility = View.VISIBLE }
                    else bounce(view, now.toString())
                }
            }, 350)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) note("Load error ${error.errorCode}: ${request.url}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) note("HTTP ${response.statusCode}: ${request.url}")
        }

        override fun onPageFinished(view: WebView, url: String) {
            refresh.isRefreshing = false
            if (view.visibility != View.VISIBLE) {
                // Uncover after the hide script has had a moment to run, so the bar never flashes either.
                view.evaluateJavascript(Marketplace.HIDE_CHROME_JS) { view.postDelayed({ view.visibility = View.VISIBLE }, 120) }
            }
            view.postDelayed({ view.invalidate() }, 400) // nudge a repaint; some pages stay white otherwise
            val uri = Uri.parse(url)
            // Wrong identity for where we ended up (e.g. just logged in, landed on Marketplace
            // as "desktop")? Switch and reload so the page comes back in the right layout.
            if (Marketplace.isFacebookHost(uri) && applyUaFor(uri)) {
                view.postDelayed({ view.loadUrl(if (Marketplace.isLoggedIn()) Marketplace.HOME else url) }, 100)
                return
            }
            if (uri.path.orEmpty().startsWith("/marketplace")) {
                view.evaluateJavascript(Marketplace.HIDE_CHROME_JS, null)
            }
            if (Marketplace.DEBUG_TOASTS) {
                view.evaluateJavascript(Marketplace.DIAG_JS) { note("DONE $it") }
            }
        }
    }

    /** Lets the page open the system file picker (needed to attach photos to a listing). */
    private inner class FileChooserChromeClient : WebChromeClient() {
        private var jsErrorsShown = 0

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            if (Marketplace.DEBUG_TOASTS && msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR && jsErrorsShown < 3) {
                jsErrorsShown++
                note("JS ${msg.message().take(90)} @ ${msg.sourceId().substringAfterLast('/').take(40)}:${msg.lineNumber()}")
            }
            return false
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return runCatching { filePicker.launch(params.createIntent()); true }
                .getOrElse { filePathCallback = null; false }
        }
    }
}
