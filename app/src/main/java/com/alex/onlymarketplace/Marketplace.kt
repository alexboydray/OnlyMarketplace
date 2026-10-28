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
    private var loginRedirectTried = false

    fun loginRedirect(uri: Uri): String? {
        if (!uri.path.orEmpty().startsWith("/unified/login_via")) return null
        // Only try the shortcut once. If Facebook sends us straight back here, let the
        // interstitial load instead; it has a "log in in browser" option.
        if (loginRedirectTried) return null
        loginRedirectTried = true
        val next = uri.getQueryParameter("next") ?: HOME
        return "https://www.facebook.com/login/?next=" + Uri.encode(next)
    }

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
          // Wrappers that collapsed to (near) zero height with overflow hidden swallow the whole
          // page. Facebook's script normally sizes them; when it crashes, they stay collapsed.
          var opened = [];
          document.querySelectorAll('body, body *').forEach(function (el) {
            var s = getComputedStyle(el);
            var clips = /hidden|clip/.test(s.overflow + s.overflowY);
            if (clips && el.clientHeight < 40 && el.scrollHeight > el.clientHeight + 40) {
              opened.push(el.tagName + (el.id ? '#' + el.id : '') + ' ' + el.clientHeight + '/' + el.scrollHeight);
              el.style.setProperty('overflow', 'visible', 'important');
              el.style.setProperty('height', 'auto', 'important');
              el.style.setProperty('min-height', '0', 'important');
            }
          });
          var i = document.querySelector('input, iframe, button, [role=button]');
          if (!i) return 'opened=[' + opened.join(' | ') + '] no anchor element';
          var found = [], e = i;
          while (e && e !== document) {
            var s = getComputedStyle(e);
            var bad = (s.visibility !== 'visible' ? 'vis=' + s.visibility : '') +
                      (s.opacity === '0' ? ' op=0' : '') +
                      (s.display === 'none' ? ' display=none' : '') +
                      (s.clipPath !== 'none' ? ' clip' : '') +
                      (s.transform !== 'none' ? ' tf' : '');
            if (bad) {
              found.push(e.tagName + (e.id ? '#' + e.id : '') + '[' + bad.trim() + ']');
              e.style.setProperty('visibility', 'visible', 'important');
              e.style.setProperty('opacity', '1', 'important');
              e.style.setProperty('display', e.tagName === 'INPUT' ? 'inline-block' : 'block', 'important');
              e.style.setProperty('transform', 'none', 'important');
              e.style.setProperty('clip-path', 'none', 'important');
            }
            e = e.parentElement;
          }
          // Full-viewport fixed/absolute elements that don't contain the form are overlays
          // (splash screens, loaders). Hit-testing ignores them when pointer-events is none.
          var killed = [];
          document.querySelectorAll('body *').forEach(function (el) {
            if (el.contains(i)) return;
            var s = getComputedStyle(el);
            if (s.position !== 'fixed' && s.position !== 'absolute') return;
            var r = el.getBoundingClientRect();
            if (r.width >= innerWidth * 0.85 && r.height >= innerHeight * 0.6) {
              killed.push(el.tagName + (el.id ? '#' + el.id : '') + '.' + String(el.className).slice(0, 20) +
                ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' bg=' + s.backgroundColor + ' pe=' + s.pointerEvents + ' z=' + s.zIndex);
              el.style.setProperty('display', 'none', 'important');
            }
          });
          // Ancestors that clip (overflow hidden) to a box smaller than the input's position.
          var clipped = [];
          var ir = i.getBoundingClientRect();
          for (e = i.parentElement; e && e !== document.body; e = e.parentElement) {
            var cs = getComputedStyle(e), cr = e.getBoundingClientRect();
            if (cs.overflow !== 'visible' && (cr.height < 40 || cr.width < 40 || cr.bottom < ir.top)) {
              clipped.push(e.tagName + (e.id ? '#' + e.id : '') + ' ' + Math.round(cr.width) + 'x' + Math.round(cr.height));
              e.style.setProperty('overflow', 'visible', 'important');
            }
          }
          var kids = Array.prototype.slice.call(document.body.children, 0, 6).map(function (k) {
            var ks = getComputedStyle(k), kr = k.getBoundingClientRect();
            return k.tagName + (k.id ? '#' + k.id : '') + ':' + ks.position + ' ' + Math.round(kr.width) + 'x' + Math.round(kr.height) + ' ' + ks.backgroundColor;
          });
          return 'opened=[' + opened.join(' | ') + '] hidden=[' + found.join(' > ') + '] overlays=[' + killed.join(' | ') + '] clipped=[' + clipped.join(' | ') + '] body>' + kids.join(', ');
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
