package com.alex.onlymarketplace

import android.net.Uri
import android.webkit.CookieManager

/**
 * The rules for what the app is allowed to show. Everything that decides
 * "is this Marketplace?" lives here so it's easy to adjust.
 */
object Marketplace {

    const val HOME = "https://m.facebook.com/marketplace/"
    const val LOGIN = "https://www.facebook.com/login/?next=$HOME"

    /** True once Facebook has set its logged-in user cookie. */
    fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://www.facebook.com")
            ?.contains("c_user=") == true

    /**
     * Facebook's session can expire server-side while the login cookie is still on the phone.
     * Then Marketplace keeps redirecting to "/" (the login page) and we keep blocking it.
     * Clearing the session cookies makes [isLoggedIn] false so the login page is allowed again.
     */
    fun forgetSession() {
        val cm = CookieManager.getInstance()
        for (host in listOf("https://www.facebook.com", "https://m.facebook.com", "https://facebook.com")) {
            for (name in listOf("c_user", "xs", "fr", "presence")) {
                cm.setCookie(host, "$name=; Max-Age=0; Path=/; Domain=.facebook.com")
                cm.setCookie(host, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
    }

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

    /** Set false to also block chatting with buyers/sellers via Messenger. */
    private const val ALLOW_MESSAGES = true

    // fb.me / fb.watch are Facebook's short links; keep them in the WebView so Android can't
    // deep-link them into the Facebook app. They redirect to facebook.com and hit the lock.
    private val FACEBOOK_HOSTS = listOf("facebook.com", "fb.com", "fb.me", "fb.watch", "fbcdn.net", "fbsbx.com")

    /** Path prefixes that may load. Everything else on facebook.com bounces to [HOME]. */
    private val ALLOWED_PREFIXES = listOf(
        "/marketplace",
        // Reached from inside Marketplace: the search box, and the full-screen photo viewer
        "/search", "/photo",
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
          // Everything below is incremental: the whole page is scanned once, then only the
          // nodes Facebook adds afterwards (infinite scroll). Text is checked before anything is
          // measured, because measuring forces layout and that is what made scrolling crawl.
          if (window.__omScan) { window.__omScan(document.body); return; }

          var NAV = /^\/(\?|home\.php|friends|notifications|watch|reel|video|groups|gaming|bookmarks|menu)?(\/|\?|$)/;
          var ICON = /^(Home|Friends|Messenger|Messages|Video|Reels|Watch|Notifications|Menu)(,|$)/;
          var UPSELL = /^(Open app|Open in app|Use app|Get app)$/i;
          function pathOf(a) { try { var u = new URL(a.getAttribute('href'), location.href); return u.pathname + u.search; } catch (e) { return ''; } }
          function hide(el) { if (el && el.style.display !== 'none') el.style.setProperty('display', 'none', 'important'); }
          function isNavLink(el) { var h = el.getAttribute('href'); if (!h) return false; var p = pathOf(el); return p === '/' || NAV.test(p); }
          function isNavIcon(el) { var l = el.getAttribute('aria-label'); return !!l && ICON.test(l); }

          // The bar is the smallest box holding the first and last nav icons we found.
          function hideBarAround(icons) {
            if (icons.length < 2) return;
            var a = icons[0], b = icons[icons.length - 1], box = a.parentElement;
            while (box && box !== document.body && !box.contains(b)) box = box.parentElement;
            if (box && box !== document.body && box.getBoundingClientRect().height < 200) hide(box);
          }
          // Ad card: climb to the grid cell (last ancestor narrower than the page), hide it,
          // then collapse any wrapper left with nothing visible so there is no gap.
          function hideAdCell(label) {
            var cell = label, p = label.parentElement;
            while (p && p !== document.body && p.getBoundingClientRect().width < innerWidth * 0.9) { cell = p; p = p.parentElement; }
            if (cell === label || cell.getBoundingClientRect().height < 80) return;
            hide(cell);
            var c = cell.parentElement;
            while (c && c !== document.body) {
              var visible = Array.prototype.some.call(c.children, function (k) {
                return k.style.display !== 'none' && k.getBoundingClientRect().height > 0;
              });
              if (visible) break;
              hide(c); c = c.parentElement;
            }
          }
          // "Open app" upsell: hide the fixed/sticky strip it sits in.
          function hideUpsell(el) {
            var box = el, p = el.parentElement;
            while (p && p !== document.body) {
              var pos = getComputedStyle(p).position;
              if (pos === 'fixed' || pos === 'sticky') { box = p; break; }
              p = p.parentElement;
            }
            hide(box);
          }
          function scan(root) {
            if (!root || root.nodeType !== 1) return;
            var icons = [];
            if (isNavIcon(root)) icons.push(root);
            var all = root.querySelectorAll('a[href], [aria-label], span, div, button, [role=button]');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (isNavIcon(el)) { icons.push(el); hide(el); continue; }
              if (el.hasAttribute('href') && isNavLink(el)) { hide(el); continue; }
              if (el.children.length === 0) {
                var t = el.textContent.trim();
                if (t === 'Ad' || t === 'Sponsored') hideAdCell(el);
                else if (UPSELL.test(t)) hideUpsell(el);
              }
            }
            hideBarAround(icons);
          }
          window.__omScan = scan;
          scan(document.body);

          var pending = [];
          new MutationObserver(function (records) {
            for (var r = 0; r < records.length; r++) {
              var added = records[r].addedNodes;
              for (var n = 0; n < added.length; n++) if (added[n].nodeType === 1) pending.push(added[n]);
            }
            if (pending.length && !window.__omTimer) {
              window.__omTimer = setTimeout(function () {
                window.__omTimer = 0;
                var batch = pending; pending = [];
                for (var i = 0; i < batch.length; i++) if (batch[i].isConnected) scan(batch[i]);
              }, 100);
            }
          }).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()
}
