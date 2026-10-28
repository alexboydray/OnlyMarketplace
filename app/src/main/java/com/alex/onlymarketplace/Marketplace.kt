package com.alex.onlymarketplace

import android.net.Uri
import android.webkit.CookieManager

/**
 * The rules for what the app is allowed to show. Everything that decides
 * "is this Marketplace?" lives here so it's easy to adjust.
 */
object Marketplace {

    const val HOME = "https://www.facebook.com/marketplace/"
    const val LOGIN = "https://www.facebook.com/login/?next=$HOME"

    /** True once Facebook has set its logged-in user cookie. */
    fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://www.facebook.com")
            ?.contains("c_user=") == true

    /**
     * Facebook's "log in via the Facebook app" interstitial (/unified/login_via/app/) tries to
     * hand off to the native app. Skip it and go straight to the web login form instead.
     */
    fun loginRedirect(uri: Uri): String? {
        if (!uri.path.orEmpty().startsWith("/unified/login_via")) return null
        val next = uri.getQueryParameter("next") ?: HOME
        return "https://www.facebook.com/login/?next=" + Uri.encode(next)
    }

    /**
     * Facebook's mobile login page relies on a script that crashes inside a WebView and leaves
     * the page collapsed. The desktop login page is a plain form, so while logged out and off
     * Marketplace we present a desktop browser, then switch back to mobile once signed in.
     */
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun wantsDesktop(uri: Uri): Boolean =
        !isLoggedIn() && isFacebookHost(uri) && !uri.path.orEmpty().startsWith("/marketplace")

    /** Shows a toast with the URL whenever the app blocks or redirects something. Turn off once stable. */
    const val DEBUG_TOASTS = false

    /** One-line page report for the debug strip: is the content there, visible, and on screen? */
    val DIAG_JS = """
        (function () {
          var b = document.body; if (!b) return 'no body';
          var cs = getComputedStyle(b);
          var i = document.querySelector('input');
          var r = i ? i.getBoundingClientRect() : null;
          return document.title.slice(0, 24) + ' | op=' + cs.opacity + ' vis=' + cs.visibility +
            ' w=' + innerWidth + ' h=' + document.documentElement.scrollHeight + ' sy=' + scrollY +
            ' input@' + (r ? Math.round(r.top) + ',' + Math.round(r.left) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) : 'none') +
            ' | ' + b.innerText.slice(0, 70).replace(/\s+/g, ' ');
        })()
    """.trimIndent()

    /** Set false to also block chatting with buyers/sellers via Messenger. */
    private const val ALLOW_MESSAGES = true

    private val FACEBOOK_HOSTS = listOf("facebook.com", "fb.com", "fbcdn.net", "fbsbx.com")

    /** Path prefixes that may load. Everything else on facebook.com bounces to [HOME]. */
    private val ALLOWED_PREFIXES = listOf(
        "/marketplace",
        // Login, 2FA, account checkpoints, password recovery
        "/login", "/checkpoint", "/two_step_verification", "/recover", "/auth", "/x/", "/unified",
        // Consent / cookie / policy pages Facebook can force you through
        "/privacy", "/cookie", "/security", "/legal", "/policies", "/help", "/dialog",
        // Lets you sign out from the profile menu
        "/logout", "/settings",
    )

    private val MESSAGE_PREFIXES = listOf("/messages", "/messenger", "/t/")

    fun isFacebookHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return FACEBOOK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isAllowed(uri: Uri): Boolean {
        val path = uri.path.orEmpty().ifEmpty { "/" }
        // Logged-out facebook.com/ IS the login page, so let it through until there's a session.
        if ((path == "/" || path == "/home.php") && !isLoggedIn()) return true
        if (ALLOWED_PREFIXES.any { path.startsWith(it) }) return true
        if (ALLOW_MESSAGES && MESSAGE_PREFIXES.any { path.startsWith(it) }) return true
        // Static assets (scripts, images, CDN) have their own hosts and never hit the path rules,
        // but allow anything that isn't a page on the main site just in case.
        val host = uri.host?.lowercase().orEmpty()
        return !host.endsWith("facebook.com") && !host.endsWith("fb.com")
    }

    /**
     * Best-effort cosmetics: hides Facebook's nav items that would lead out of Marketplace.
     * The navigation lock above does the real work; this just removes the temptation.
     * Facebook renames its CSS classes constantly, so this targets aria-labels and hrefs,
     * which are far more stable. Re-run on each page load because the SPA re-renders the shell.
     */
    val HIDE_CHROME_JS = """
        (function () {
          if (!document.getElementById('om-hide')) {
            var css = [
              'a[aria-label="Home"]', 'a[aria-label="Video"]', 'a[aria-label="Groups"]',
              'a[aria-label="Friends"]', 'a[aria-label="Gaming"]', 'a[aria-label="Reels"]',
              'div[aria-label="Notifications"]', 'a[aria-label="Notifications"]',
              'div[aria-label="Menu"]', 'a[aria-label="Menu"]',
              'a[aria-label="News Feed"]', 'a[aria-label="Feed"]'
            ].join(',') + '{display:none !important;}';
            var style = document.createElement('style');
            style.id = 'om-hide';
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
          }
          // Paths that belong to Facebook's main nav, not Marketplace.
          var NAV = /^\/(\?|home\.php|friends|notifications|watch|reel|video|groups|gaming|bookmarks|menu)?(\/|\?|$)/;
          function pathOf(a) { try { var u = new URL(a.getAttribute('href'), location.href); return u.pathname + u.search; } catch (e) { return ''; } }
          function hideBar() {
            var links = Array.prototype.slice.call(document.querySelectorAll('a[href]')).filter(function (a) {
              var p = pathOf(a); return p === '/' || NAV.test(p);
            });
            var home = null, notif = null;
            links.forEach(function (a) {
              var p = pathOf(a);
              if (!home && (p === '/' || /^\/(\?|home\.php)/.test(p))) home = a;
              if (!notif && /^\/notifications/.test(p)) notif = a;
              a.style.setProperty('display', 'none', 'important');
            });
            // The whole bar is the smallest box holding both Home and Notifications.
            if (home && notif) {
              var box = home.parentElement;
              while (box && box !== document.body && !box.contains(notif)) box = box.parentElement;
              if (box && box !== document.body && box.getBoundingClientRect().height < 200) {
                box.style.setProperty('display', 'none', 'important');
              }
            }
          }
          // Ads: a card with a small "Ad" / "Sponsored" label. Hide the grid cell it lives in,
          // i.e. the closest ancestor just below the first full-width container.
          function hideAds() {
            var labels = Array.prototype.slice.call(document.querySelectorAll('span, div, a')).filter(function (el) {
              return el.children.length === 0 && /^(Ad|Sponsored)$/.test(el.textContent.trim()) &&
                     el.getBoundingClientRect().height < 32;
            });
            labels.forEach(function (label) {
              var cell = label, p = label.parentElement;
              while (p && p !== document.body && p.getBoundingClientRect().width < innerWidth * 0.9) { cell = p; p = p.parentElement; }
              if (cell !== label && cell.getBoundingClientRect().height > 80) cell.style.setProperty('display', 'none', 'important');
            });
          }
          function tidy() { hideBar(); hideAds(); }
          tidy();
          if (!window.__omObserver) {
            window.__omObserver = new MutationObserver(function () {
              clearTimeout(window.__omTimer);
              window.__omTimer = setTimeout(tidy, 150);
            });
            window.__omObserver.observe(document.documentElement, { childList: true, subtree: true });
          }
        })();
    """.trimIndent()
}
