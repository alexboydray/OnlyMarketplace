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
    const val DEBUG_TOASTS = true

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

    /**
     * Facebook's login page sometimes arrives hidden and the script meant to reveal it crashes.
     * Walk up from the first input, report anything hiding it, and force it visible. Login is a
     * plain form post, so it works without Facebook's script.
     */
    val REVEAL_LOGIN_JS = """
        (function () {
          var i = document.querySelector('input, iframe, button, [role=button]');
          if (!i) return 'no anchor';
          // Brute force: on every ancestor of the form, reset every property that can clip,
          // collapse or hide it, and record what each one looked like before.
          var chain = [], e = i;
          while (e && e.nodeType === 1) {
            var s = getComputedStyle(e), r = e.getBoundingClientRect();
            chain.push(e.tagName + (e.id ? '#' + e.id : '') + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.top) +
              ' ov=' + s.overflow + ' ct=' + s.contain + ' cv=' + s.contentVisibility + ' cl=' + s.clip +
              ' pos=' + s.position + ' vis=' + s.visibility + ' op=' + s.opacity + ' mh=' + s.maxHeight + ' d=' + s.display);
            var st = e.style;
            st.setProperty('overflow', 'visible', 'important');
            st.setProperty('contain', 'none', 'important');
            st.setProperty('content-visibility', 'visible', 'important');
            st.setProperty('clip', 'auto', 'important');
            st.setProperty('clip-path', 'none', 'important');
            st.setProperty('max-height', 'none', 'important');
            st.setProperty('visibility', 'visible', 'important');
            st.setProperty('opacity', '1', 'important');
            st.setProperty('transform', 'none', 'important');
            st.setProperty('filter', 'none', 'important');
            if (s.display === 'none') st.setProperty('display', 'block', 'important');
            if (r.height < 40 && e !== i && e.tagName !== 'HTML') st.setProperty('height', 'auto', 'important');
            e = e.parentElement;
          }
          // Full-viewport fixed/absolute elements that don't contain the form are overlays
          // (splash screens, loaders). Hit-testing ignores them when pointer-events is none.
          var killed = [];
          document.querySelectorAll('body *').forEach(function (el) {
            if (el === i || el.contains(i) || i.contains(el)) return;
            var s = getComputedStyle(el);
            if (s.position !== 'fixed' && s.position !== 'absolute') return;
            var r = el.getBoundingClientRect();
            if (r.width >= innerWidth * 0.85 && r.height >= innerHeight * 0.6) {
              killed.push(el.tagName + (el.id ? '#' + el.id : '') + ' bg=' + s.backgroundColor + ' z=' + s.zIndex);
              el.style.setProperty('display', 'none', 'important');
            }
          });
          var cv = i.checkVisibility ? i.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }) : 'n/a';
          var r2 = i.getBoundingClientRect();
          return 'visible=' + cv + ' anchor=' + i.tagName + '@' + Math.round(r2.top) + ',' + Math.round(r2.left) +
            ' killed=[' + killed.join(' | ') + '] chain=' + chain.join(' < ');
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
          if (document.getElementById('om-hide')) return;
          var css = [
            // Top nav shortcuts (desktop + mobile web)
            'a[aria-label="Home"]', 'a[aria-label="Video"]', 'a[aria-label="Groups"]',
            'a[aria-label="Friends"]', 'a[aria-label="Gaming"]', 'a[aria-label="Reels"]',
            'a[href="/"]', 'a[href="https://www.facebook.com/"]',
            'a[href^="/watch"]', 'a[href^="/groups"]', 'a[href^="/gaming"]', 'a[href^="/reel"]',
            // Notifications bell and the "Menu" grid
            'div[aria-label="Notifications"]', 'a[aria-label="Notifications"]',
            'div[aria-label="Menu"]', 'a[aria-label="Menu"]',
            // Mobile web bottom tab bar entries
            'a[aria-label="News Feed"]', 'a[aria-label="Feed"]'
          ].join(',') + '{display:none !important;}';
          var style = document.createElement('style');
          style.id = 'om-hide';
          style.textContent = css;
          (document.head || document.documentElement).appendChild(style);
        })();
    """.trimIndent()
}
