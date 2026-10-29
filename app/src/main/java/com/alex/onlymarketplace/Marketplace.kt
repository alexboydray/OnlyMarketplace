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
            ' | top=' + (window.__omTop || '-');
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
          // The nav bar's icons are font glyphs (private-use characters), so its text is only
          // glyphs, badge digits and "+". A full-width strip 40–130px tall with that kind of
          // text and a handful of tappable children is the bar. Only checked near the top of the
          // tree (bars live there) so it stays cheap.
          var GLYPHS_ONLY = /^[^A-Za-z]*$/;
          // The bar carries hidden labels naming the tabs; ignore those words when testing.
          var TAB_WORDS = /Marketplace|Home|Friends|Messenger|Messages|Video|Reels|Watch|Notifications|Menu|Dating|Groups/g;
          function iconsOnly(el) { return GLYPHS_ONLY.test(el.textContent.replace(TAB_WORDS, '')); }
          // The Marketplace header (back, title, search) is also icons-only once tab words are
          // ignored, but it has 3 cells; the nav bar has 6. Require 5+ cells in the first two levels.
          function cellCount(el) {
            // Count distinct columns of icon-sized boxes (40–130px wide), however deeply nested.
            var cols = {}, all = el.querySelectorAll('*');
            for (var i = 0; i < all.length && i < 150; i++) {
              var r = all[i].getBoundingClientRect();
              if (r.width >= 40 && r.width <= 130 && r.height >= 24) cols[Math.round(r.left / 40)] = 1;
            }
            return Object.keys(cols).length;
          }
          // Any *visible* run of letters (a title like "Marketplace", a chip like "Inbox") rules
          // the element out; the bar's only letters are hidden labels with no size.
          function hasVisibleWords(el) {
            var box = el.getBoundingClientRect();
            var leaves = el.querySelectorAll('*');
            window.__omVis = '';
            for (var i = 0; i < leaves.length && i < 200; i++) {
              var n = leaves[i];
              if (n.children.length !== 0 || !/[A-Za-z]{2,}/.test(n.textContent)) continue;
              var r = n.getBoundingClientRect();
              if (r.width < 30 || r.height < 10) continue;
              // Must actually sit inside the strip's own box (off-screen labels don't).
              if (r.right <= box.left || r.left >= box.right || r.bottom <= box.top || r.top >= box.bottom) continue;
              var s = getComputedStyle(n);
              if (s.visibility !== 'visible' || s.opacity === '0' || s.clip !== 'auto' || s.clipPath !== 'none' ||
                  parseFloat(s.fontSize) < 8 || s.color === 'rgba(0, 0, 0, 0)') continue;
              // The decisive test: is this text what's actually painted at its own centre?
              // Labels clipped by a zero-size parent measure fine but something else is on top.
              var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
              if (!hit || (hit !== n && !n.contains(hit))) continue;
              window.__omVis = n.textContent.trim().slice(0, 20) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.top) + ',' + Math.round(r.left);
              return true;
            }
            return false;
          }
          // Icon glyphs are leaf elements whose whole text is one or two non-letter, non-digit
          // characters. The nav bar has six of them; the Marketplace header has two.
          function glyphCount(el) {
            var n = 0, leaves = el.querySelectorAll('*');
            for (var i = 0; i < leaves.length && i < 200; i++) {
              var t = leaves[i].children.length === 0 ? leaves[i].textContent.trim() : '';
              if (t.length >= 1 && t.length <= 2 && !/[A-Za-z0-9+]/.test(t)) n++;
            }
            return n;
          }
          function isIconBar(el) { return iconsOnly(el) && glyphCount(el) >= 5 && cellCount(el) >= 5; }
          function hideStrip(root) {
            var els = root.querySelectorAll('div, nav, header');
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (el.childElementCount === 0 || el.querySelectorAll('*').length > 120) continue;
              if (!isIconBar(el)) continue;
              var r = el.getBoundingClientRect();
              if (r.width < innerWidth * 0.9 || r.height < 40 || r.height > 130) continue;
              hide(el);
            }
          }
          // Blunter fallback: whatever is drawn across the very top of the page, climb to its
          // full-width container and hide it if it has no real words (the Marketplace header
          // below it says "Marketplace", so that one survives).
          function hideTopBarByPoint() {
            if (scrollY > 2) return;
            var xs = [innerWidth * 0.1, innerWidth * 0.5, innerWidth * 0.9], report = [];
            for (var k = 0; k < xs.length; k++) {
              var el = document.elementFromPoint(xs[k], 25);
              while (el && el !== document.body) {
                var r = el.getBoundingClientRect();
                if (k === 0) report.push(el.tagName + (el.id ? '#' + el.id : '') + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' t=' + el.textContent.replace(/\s+/g, '').length);
                if (r.width >= innerWidth * 0.9 && r.height >= 40 && r.height <= 130) {
                  if (isIconBar(el)) { hide(el); if (k === 0) report.push('HID'); }
                  else if (k === 0) report.push('cells=' + cellCount(el) + ' glyphs=' + glyphCount(el) + ' TEXT:' + el.textContent.replace(/[\d+\s]/g, '').slice(0, 30));
                  break;
                }
                el = el.parentElement;
              }
            }
            window.__omTop = report.join(' < ');
          }
          function depthOf(el) { var d = 0; while (el && el !== document.body && d < 9) { el = el.parentElement; d++; } return d; }
          function scan(root) {
            if (!root || root.nodeType !== 1) return;
            if (depthOf(root) <= 5) { hideStrip(root); hideTopBarByPoint(); }
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
          setTimeout(hideTopBarByPoint, 800);
          setTimeout(hideTopBarByPoint, 2500);

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
