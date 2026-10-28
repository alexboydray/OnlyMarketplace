package com.alex.onlymarketplace

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

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

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                // fb:// or intent:// links try to open the Facebook app. We don't want that;
                // if it was a login attempt, show the web login page instead.
                if (!Marketplace.isLoggedIn()) view.loadUrl(Marketplace.LOGIN)
                return true
            }
            if (!Marketplace.isFacebookHost(url)) {
                // Off-site link (e.g. a seller's external website): hand it to the system browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
            if (Marketplace.isAllowed(url)) return false // let the WebView load it
            view.loadUrl(Marketplace.HOME) // anything else bounces back to Marketplace
            return true
        }

        // Facebook is a single-page app: most clicks change the URL with pushState, which never
        // reaches shouldOverrideUrlLoading. This hook fires for those, so we re-check here too.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val uri = Uri.parse(url)
            if (Marketplace.isFacebookHost(uri) && !Marketplace.isAllowed(uri)) {
                view.loadUrl(Marketplace.HOME)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            refresh.isRefreshing = false
            if (Marketplace.isFacebookHost(Uri.parse(url))) {
                view.evaluateJavascript(Marketplace.HIDE_CHROME_JS, null)
            }
        }
    }

    /** Lets the page open the system file picker (needed to attach photos to a listing). */
    private inner class FileChooserChromeClient : WebChromeClient() {
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
