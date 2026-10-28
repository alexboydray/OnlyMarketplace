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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout

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
        setContentView(refresh)

        // Android 15 draws edge-to-edge; keep the page out from under the status/nav bars.
        WindowCompat.getInsetsController(window, refresh).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(refresh) { v, insets ->
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
        }

        // WebViews send "X-Requested-With: <package>" on every request, which is how Facebook
        // spots in-app browsers and forces the "log in via the Facebook app" page. Drop it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
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

        private fun note(msg: String) {
            if (Marketplace.DEBUG_TOASTS) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }

        /** Send the view back to Marketplace. Posted so it never collides with the navigation that triggered it. */
        private fun bounce(view: WebView, from: String, target: String = Marketplace.HOME) {
            if (from == lastBounce) return // already handling this one
            lastBounce = from
            note(if (target == Marketplace.HOME) "Blocked: $from" else "Redirecting to login (v${BuildConfig.VERSION_NAME})")
            view.post { view.loadUrl(target) }
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
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
            Marketplace.loginRedirect(url)?.let { bounce(view, url.toString(), it); return true }
            if (Marketplace.isAllowed(url)) return false // let the WebView load it
            bounce(view, url.toString()) // anything else bounces back to Marketplace
            return true
        }

        // Facebook is a single-page app: most clicks change the URL with pushState, which never
        // reaches shouldOverrideUrlLoading. This hook fires for those, so we re-check here too.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val uri = Uri.parse(url)
            if (!Marketplace.isFacebookHost(uri)) return
            Marketplace.loginRedirect(uri)?.let { bounce(view, url, it); return }
            if (!Marketplace.isAllowed(uri)) bounce(view, url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) note("Load error ${error.errorCode}: ${request.url}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) note("HTTP ${response.statusCode}: ${request.url}")
        }

        override fun onPageFinished(view: WebView, url: String) {
            refresh.isRefreshing = false
            if (Marketplace.isFacebookHost(Uri.parse(url))) {
                view.evaluateJavascript(Marketplace.HIDE_CHROME_JS, null)
            }
            if (Marketplace.DEBUG_TOASTS) {
                view.evaluateJavascript(
                    "(document.body ? document.body.innerText.length : -1) + ' chars, ' + document.querySelectorAll('input').length + ' inputs | ' + document.title"
                ) { note("Page: $it") }
            }
        }
    }

    /** Lets the page open the system file picker (needed to attach photos to a listing). */
    private inner class FileChooserChromeClient : WebChromeClient() {
        private var jsErrorsShown = 0

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            if (Marketplace.DEBUG_TOASTS && msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR && jsErrorsShown < 3) {
                jsErrorsShown++
                Toast.makeText(this@MainActivity, "JS: ${msg.message().take(120)}", Toast.LENGTH_LONG).show()
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
