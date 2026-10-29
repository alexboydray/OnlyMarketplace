package com.alex.onlymarketplace

import android.net.Uri
import android.webkit.CookieManager

/**
 * The rules for what the app is allowed to show. Everything that decides
 * "is this Marketplace?" lives here so it's easy to adjust.
 */
object Marketplace {

    /** Bump to make the next launch wipe all site data once (fresh browser identity). */
    const val RESET_GENERATION = 2 // 2: switched to a single mobile identity

    const val HOME = "https://m.facebook.com/marketplace/"
    const val LOGIN = "https://m.facebook.com/login/?next=$HOME"

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
        return "https://m.facebook.com/login/?next=" + Uri.encode(next)
    }

    /**
     * Facebook's mobile login / checkpoint pages are built from fixed-height blocks (#viewport,
     * MContainer divs with inline heights and overflow hidden) that a script is supposed to size
     * once the page loads. That script crashes inside a WebView, so the blocks stay at their
     * initial near-zero height and clip everything. This opens every such block so the page
     * lays out naturally. Only ever run on non-Marketplace pages.
     */
    val UNCLIP_JS = """
        (function () {
          if (!document.getElementById('om-unclip')) {
            var s = document.createElement('style');
            s.id = 'om-unclip';
            s.textContent = 'html, #viewport, #root, #screen-root { height: auto !important; min-height: 100vh !important; overflow: visible !important; }' +
                            'body { height: auto !important; min-height: 100vh !important; overflow: visible !important; position: static !important; }';
            (document.head || document.documentElement).appendChild(s);
          }
          // Measure, don't guess: any clipping box under 60px tall that holds more than it
          // shows gets opened. Deepest first, so once a child is opened its parent measures
          // taller and opens too. Skips screen-reader-only boxes (clip: rect(...)).
          var SKIP = /^(IMG|SVG|CANVAS|IFRAME|INPUT|TEXTAREA|SELECT|BUTTON|VIDEO|PATH|USE|G)$/;
          var all = document.querySelectorAll('body *'), opened = 0;
          for (var i = all.length - 1; i >= 0; i--) {
            var el = all[i];
            if (SKIP.test(el.tagName)) continue;
            var cs = getComputedStyle(el);
            if (!/hidden|clip/.test(cs.overflow + cs.overflowY)) continue;
            if (cs.clip !== 'auto') continue;
            var h = el.getBoundingClientRect().height;
            if (h >= 60) continue;
            var holdsMore = el.scrollHeight > h + 8 || (h < 2 && (el.children.length > 0 || el.textContent.trim().length > 0));
            if (!holdsMore) continue;
            el.style.setProperty('overflow', 'visible', 'important');
            el.style.setProperty('height', 'auto', 'important');
            el.style.setProperty('min-height', '0', 'important');
            el.style.setProperty('max-height', 'none', 'important');
            opened++;
          }
          // The form is laid out fine but painted over: Facebook's full-screen loading overlay
          // stays up when its script crashes. Anchor on the biggest form element (field, captcha
          // iframe, button), then hide every full-viewport fixed/absolute box that doesn't
          // contain it. Hit-testing can't find these (pointer-events: none), so measure instead.
          var anchor = null, best = 0;
          var cands = document.querySelectorAll('input:not([type=hidden]), iframe, button, [role=button]');
          for (var c = 0; c < cands.length; c++) {
            var cr = cands[c].getBoundingClientRect(), area = cr.width * cr.height;
            if (area > best) { best = area; anchor = cands[c]; }
          }
          var killed = [];
          if (anchor) {
            var boxes = document.querySelectorAll('body *');
            for (var b = 0; b < boxes.length; b++) {
              var box = boxes[b];
              if (box === anchor || box.contains(anchor) || anchor.contains(box)) continue;
              var bs = getComputedStyle(box);
              if (bs.position !== 'fixed' && bs.position !== 'absolute') continue;
              var br = box.getBoundingClientRect();
              if (br.width < innerWidth * 0.85 || br.height < innerHeight * 0.6) continue;
              killed.push(box.tagName + (box.id ? '#' + box.id : '') + ' ' + Math.round(br.width) + 'x' + Math.round(br.height) + ' bg=' + bs.backgroundColor + ' z=' + bs.zIndex + ' pe=' + bs.pointerEvents);
              box.style.setProperty('display', 'none', 'important');
            }
            // And make sure nothing on the anchor's own chain hides it.
            for (var e = anchor; e && e !== document.documentElement; e = e.parentElement) {
              var es = getComputedStyle(e);
              if (es.visibility !== 'visible') e.style.setProperty('visibility', 'visible', 'important');
              if (es.opacity === '0') e.style.setProperty('opacity', '1', 'important');
              if (es.display === 'none') e.style.setProperty('display', 'block', 'important');
            }
          }
          window.__omUnclip = 'opened=' + opened + ' anchor=' + (anchor ? anchor.tagName + ' ' + Math.round(best) + 'px²' : 'none') + ' killed=[' + killed.join(' ; ') + ']';
          return opened;
        })()
    """.trimIndent()

    /** Compact readout for login/checkpoint pages: is there a form, and is it on screen? */
    const val AUTH_DIAG = true
    val AUTH_DIAG_JS = """
        (function () {
          var i = document.querySelector('input:not([type=hidden])');
          var r = i ? i.getBoundingClientRect() : null;
          var t = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').trim();
          return document.title.slice(0, 22) + ' | page h=' + document.documentElement.scrollHeight + ' text=' + t.length +
            ' input=' + (r ? Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.top) : 'none') +
            ' | ' + (window.__omUnclip || 'unclip not run');
        })()
    """.trimIndent()

    /**
     * Full page report for a login/checkpoint page that renders blank. Everything that could
     * explain "laid out but not painted": structure, the form field's ancestor chain with full
     * styles, positioned and large white boxes, the paint stack at several points, pseudo
     * elements, and a slice of raw HTML. Copied to the clipboard to paste into chat.
     */
    val DIAG_FULL_JS = """
        (function () {
          try {
            function R(el) { var r = el.getBoundingClientRect(); return Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height); }
            function tag(el) {
              var c = (el.className && typeof el.className === 'string') ? el.className.trim().split(/\s+/).slice(0, 3).join('.') : '';
              return el.tagName + (el.id ? '#' + el.id : '') + (c ? '.' + c : '') + (el.getAttribute && el.getAttribute('role') ? '[role=' + el.getAttribute('role') + ']' : '');
            }
            function sty(el) {
              var s = getComputedStyle(el);
              return 'd=' + s.display + ' pos=' + s.position + ' ov=' + s.overflow + ' h=' + s.height + ' w=' + s.width + ' op=' + s.opacity + ' vis=' + s.visibility +
                ' tf=' + s.transform + ' clip=' + s.clip + ' cp=' + s.clipPath + ' filt=' + s.filter + ' bg=' + s.backgroundColor + ' z=' + s.zIndex +
                ' top=' + s.top + ' left=' + s.left + ' pe=' + s.pointerEvents + ' cv=' + s.contentVisibility + ' ct=' + s.contain + ' col=' + s.color;
            }
            var out = [], i, n, el, s, r;
            out.push('URL ' + location.href);
            out.push('TITLE ' + document.title + ' | readyState=' + document.readyState);
            out.push('VIEWPORT ' + innerWidth + 'x' + innerHeight + ' dpr=' + devicePixelRatio + ' scroll=' + scrollX + ',' + scrollY + ' docH=' + document.documentElement.scrollHeight + ' bodyH=' + document.body.scrollHeight);
            var vm = document.querySelector('meta[name=viewport]'); out.push('META viewport=' + (vm ? vm.content : 'none'));
            out.push('HTML ' + sty(document.documentElement));
            out.push('BODY ' + R(document.body) + ' ' + sty(document.body) + ' children=' + document.body.children.length + ' all=' + document.querySelectorAll('*').length);
            var tops = [document.documentElement, document.body].concat(Array.prototype.slice.call(document.body.children, 0, 12));
            ['::before', '::after'].forEach(function (p) {
              tops.forEach(function (t) {
                var ps = getComputedStyle(t, p);
                if (ps.content && ps.content !== 'none' && ps.content !== 'normal') out.push('PSEUDO ' + tag(t) + p + ' content=' + ps.content + ' pos=' + ps.position + ' bg=' + ps.backgroundColor + ' w=' + ps.width + ' h=' + ps.height + ' z=' + ps.zIndex);
              });
            });
            out.push('--- BODY CHILDREN (2 levels)');
            Array.prototype.forEach.call(document.body.children, function (k) {
              out.push(' ' + tag(k) + ' ' + R(k) + ' ' + sty(k));
              Array.prototype.slice.call(k.children, 0, 10).forEach(function (g) {
                var gs = getComputedStyle(g);
                out.push('    ' + tag(g) + ' ' + R(g) + ' d=' + gs.display + ' pos=' + gs.position + ' ov=' + gs.overflow + ' bg=' + gs.backgroundColor + ' op=' + gs.opacity + ' vis=' + gs.visibility + ' z=' + gs.zIndex + ' txt="' + g.textContent.trim().replace(/\s+/g, ' ').slice(0, 40) + '"');
              });
            });
            var inputs = document.querySelectorAll('input:not([type=hidden])');
            out.push('--- INPUTS ' + inputs.length);
            Array.prototype.forEach.call(inputs, function (x) { var xs = getComputedStyle(x); out.push(' INPUT type=' + x.type + ' name=' + x.name + ' ph="' + x.placeholder + '" ' + R(x) + ' d=' + xs.display + ' vis=' + xs.visibility + ' op=' + xs.opacity + ' col=' + xs.color + ' bg=' + xs.backgroundColor); });
            var a = inputs[0] || document.querySelector('iframe, button, [role=button]');
            if (a) {
              out.push('--- CHAIN from ' + tag(a));
              for (el = a; el && el !== document; el = el.parentElement) out.push(' ' + tag(el) + ' ' + R(el) + ' ' + sty(el));
            }
            var all = document.querySelectorAll('body *');
            out.push('--- POSITIONED fixed/absolute covering >50% viewport');
            for (i = 0, n = 0; i < all.length && n < 30; i++) {
              el = all[i]; s = getComputedStyle(el);
              if (s.position !== 'fixed' && s.position !== 'absolute') continue;
              r = el.getBoundingClientRect(); if (r.width < innerWidth * 0.5 || r.height < innerHeight * 0.5) continue;
              n++; out.push(' ' + tag(el) + ' ' + R(el) + ' bg=' + s.backgroundColor + ' z=' + s.zIndex + ' pe=' + s.pointerEvents + ' op=' + s.opacity + ' vis=' + s.visibility + ' d=' + s.display + ' hasForm=' + (a ? el.contains(a) : '-'));
            }
            out.push('--- BIG LIGHT BOXES (opaque light bg, >70% viewport)');
            for (i = 0, n = 0; i < all.length && n < 20; i++) {
              el = all[i]; s = getComputedStyle(el); var bg = s.backgroundColor;
              var m = /rgba?\((\d+), ?(\d+), ?(\d+)(?:, ?([\d.]+))?\)/.exec(bg); if (!m) continue;
              if (+m[1] < 220 || +m[2] < 220 || +m[3] < 220 || (m[4] !== undefined && +m[4] < 0.5)) continue;
              r = el.getBoundingClientRect(); if (r.width < innerWidth * 0.7 || r.height < innerHeight * 0.7) continue;
              n++; out.push(' ' + tag(el) + ' ' + R(el) + ' bg=' + bg + ' pos=' + s.position + ' z=' + s.zIndex + ' op=' + s.opacity + ' hasForm=' + (a ? el.contains(a) : '-'));
            }
            out.push('--- PAINT STACK (elementsFromPoint, top first)');
            [[0.5, 0.3], [0.5, 0.5], [0.2, 0.7], [0.8, 0.12], [0.5, 0.9]].forEach(function (p) {
              var x = innerWidth * p[0], y = innerHeight * p[1], st = document.elementsFromPoint(x, y);
              out.push(' @' + Math.round(x) + ',' + Math.round(y) + ': ' + st.slice(0, 7).map(function (e) { return tag(e) + '(' + getComputedStyle(e).backgroundColor + ')'; }).join(' > '));
            });
            out.push('--- TEXT (innerText, 300 chars)');
            out.push(((document.body.innerText) || '').replace(/\s+/g, ' ').trim().slice(0, 300));
            out.push('--- UNCLIP ' + (window.__omUnclip || 'not run'));
            out.push('--- HTML (body outerHTML, 2500 chars)');
            out.push(document.body.outerHTML.replace(/\s+/g, ' ').slice(0, 2500));
            return out.join('\n');
          } catch (e) { return 'DIAG FAILED: ' + e; }
        })()
    """.trimIndent()

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
            ' | hid=[' + ((window.__omHid || []).join(' ; ')) + '] DUMP: ' + (window.__omDump || '-');
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
          // Incremental: the whole page is scanned once, then only the nodes Facebook adds
          // afterwards (infinite scroll). Text is checked before anything is measured.
          if (window.__omScan) { window.__omScan(document.body); return; }

          var NAV = /^\/(\?|home\.php|friends|notifications|watch|reel|video|groups|gaming|bookmarks|menu)?(\/|\?|$)/;
          var UPSELL = /^(Open app|Open in app|Use app|Get app)$/i;
          function pathOf(a) { try { var u = new URL(a.getAttribute('href'), location.href); return u.pathname + u.search; } catch (e) { return ''; } }
          function hide(el) { if (el && el.style.display !== 'none') el.style.setProperty('display', 'none', 'important'); }
          function isNavLink(el) { var h = el.getAttribute('href'); if (!h) return false; var p = pathOf(el); return p === '/' || NAV.test(p); }

          // Facebook's top nav bar is a role="tablist" whose role="tab" children are labelled
          // "feed, 1,800 new, 1 of 6", "friends, 2 of 6", "notifications, ..." and so on.
          function hideTabBar(root) {
            var lists = root.querySelectorAll('[role="tablist"]');
            if (root.getAttribute && root.getAttribute('role') === 'tablist') lists = [root].concat(Array.prototype.slice.call(lists));
            for (var i = 0; i < lists.length; i++) {
              var tabs = lists[i].querySelectorAll('[role="tab"]');
              if (tabs.length < 4) continue;
              var labels = Array.prototype.map.call(tabs, function (t) { return (t.getAttribute('aria-label') || '').toLowerCase(); }).join('|');
              if (!/\b(feed|friends|notifications|video|watch|reels|menu)\b/.test(labels)) continue;
              var tl = lists[i];
              // Facebook re-renders individual tabs (badge updates), so hiding must re-apply
              // every time; the container resize happens once.
              if (tl.getAttribute('data-om-done')) {
                for (var t0 = 0; t0 < tabs.length; t0++) hide(tabs[t0]);
                continue;
              }
              // The container holds two rows: the icon tabs on top and the page header
              // ("< Marketplace", search) below. Measure the icon row, hide the tabs, and take
              // the icon row's height off the container; the header then flows to the top.
              var tr = tl.getBoundingClientRect(), rowBottom = tr.top;
              for (var t = 0; t < tabs.length; t++) {
                var b = tabs[t].getBoundingClientRect().bottom;
                if (b > rowBottom) rowBottom = b;
              }
              var rowH = Math.round(rowBottom - tr.top);
              tl.setAttribute('data-om-done', '1');
              for (var t2 = 0; t2 < tabs.length; t2++) hide(tabs[t2]);
              if (rowH > 0 && rowH < tr.height - 10) {
                // With the tabs gone the header row flows to the top on its own; just take the
                // icon row's height off the container so nothing below is pushed down.
                tl.style.setProperty('height', Math.round(tr.height - rowH) + 'px', 'important');
                // The header's back arrow (first button in the row) leads out of Marketplace.
                // Blank it but keep its space: the title overlaps the row with a negative margin.
                Array.prototype.forEach.call(tl.children, function (row) {
                  if (row.getAttribute('role') === 'tab') return;
                  var back = row.querySelector('[role="button"]');
                  if (back && back.getBoundingClientRect().left < 60) {
                    back.style.setProperty('visibility', 'hidden', 'important');
                    back.style.setProperty('pointer-events', 'none', 'important');
                  }
                });
              } else {
                hide(tl); // nothing but tabs in it
              }
            }
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
            hideTabBar(root);
            // A single re-rendered tab arrives on its own; re-check the bar it belongs to.
            var bar = root.closest ? root.closest('[role="tablist"]') : null;
            if (bar && bar !== root) hideTabBar(bar);
            var all = root.querySelectorAll('a[href], span, div, button, [role=button]');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (el.hasAttribute('href') && isNavLink(el)) { hide(el); continue; }
              if (el.children.length === 0) {
                var t = el.textContent.trim();
                if (t === 'Ad' || t === 'Sponsored') hideAdCell(el);
                else if (UPSELL.test(t)) hideUpsell(el);
              }
            }
          }
          window.__omScan = scan;
          scan(document.body);

          // Never compete with scrolling: queue added nodes, and process them only once the
          // user has paused for a moment, in idle time where the browser offers it.
          var pending = [], lastScroll = 0;
          addEventListener('scroll', function () { lastScroll = Date.now(); }, { passive: true, capture: true });
          function drain() {
            window.__omTimer = 0;
            if (!pending.length) return;
            if (Date.now() - lastScroll < 300) { window.__omTimer = setTimeout(drain, 300); return; }
            var batch = pending; pending = [];
            var run = function () { for (var i = 0; i < batch.length; i++) if (batch[i].isConnected) scan(batch[i]); };
            if (window.requestIdleCallback) requestIdleCallback(run, { timeout: 1500 }); else run();
          }
          new MutationObserver(function (records) {
            for (var r = 0; r < records.length; r++) {
              var added = records[r].addedNodes;
              for (var n = 0; n < added.length; n++) if (added[n].nodeType === 1) pending.push(added[n]);
            }
            if (pending.length && !window.__omTimer) window.__omTimer = setTimeout(drain, 150);
          }).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()
}
