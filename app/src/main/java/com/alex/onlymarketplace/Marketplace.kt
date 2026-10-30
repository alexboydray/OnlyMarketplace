package com.alex.onlymarketplace

import android.net.Uri
import android.webkit.CookieManager

/**
 * The rules for what the app is allowed to show. Everything that decides
 * "is this Marketplace?" lives here so it's easy to adjust.
 */
object Marketplace {

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
    private val loginRedirects = ArrayDeque<Long>()

    fun loginRedirect(uri: Uri): String? {
        if (!uri.path.orEmpty().startsWith("/unified/login_via")) return null
        val now = System.currentTimeMillis()
        while (loginRedirects.isNotEmpty() && now - loginRedirects.first() > 15_000) loginRedirects.removeFirst()
        // The interstitial is an empty shell in a WebView (its UI never renders). The real
        // login form is the logged-out home page with ?next=. Go there first; if Facebook
        // sends us back here, try /login/; a third time, give up and let it load.
        val attempt = loginRedirects.size
        if (attempt >= 2) return null
        loginRedirects.addLast(now)
        val next = Uri.encode(uri.getQueryParameter("next") ?: HOME)
        return if (attempt == 0) "https://m.facebook.com/?next=$next" else "https://m.facebook.com/login/?next=$next"
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
            // ANY non-visible overflow clips: hidden, clip, scroll and auto alike. (The login
            // form sits in a 0px-tall overflow:scroll box — see the 0.5.6 diagnostic.)
            if (cs.overflow === 'visible' && cs.overflowY === 'visible' && cs.overflowX === 'visible') continue;
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
            // Zero-height wrappers above it would keep the document 18px tall (no scrolling),
            // so let them grow with their content too.
            for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
              if (p.getBoundingClientRect().height < 2) {
                p.style.setProperty('height', 'auto', 'important');
                p.style.setProperty('min-height', '0', 'important');
                p.style.setProperty('overflow', 'visible', 'important');
              }
            }
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

    /**
     * Listing photo strips: Facebook shows them as one free-scrolling horizontal strip. Make
     * each photo exactly one strip-width wide and snap one photo per swipe. Only touches
     * horizontal scrollers that hold 2+ images and almost no text (so the "Inbox / Sell /
     * Categories" chip row is left alone). Reports what it did in window.__omSnap.
     */
    val SNAP_JS = """
        (function () {
          // INSPECT ONLY — changes nothing. Describes the photo viewer so the one-photo-per-swipe
          // fix can be built from its real structure. Read out via the ⏱ report.
          function R(e) { var r = e.getBoundingClientRect(); return Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height); }
          function tag(e) {
            var c = (typeof e.className === 'string' && e.className) ? '.' + e.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
            return e.tagName + (e.id ? '#' + e.id : '') + c + (e.getAttribute('role') ? '[' + e.getAttribute('role') + ']' : '') + (e.getAttribute('data-sigil') ? '{' + e.getAttribute('data-sigil').slice(0, 30) + '}' : '');
          }
          function sty(e) {
            var s = getComputedStyle(e);
            return 'd=' + s.display + ' pos=' + s.position + ' ovx=' + s.overflowX + ' ovy=' + s.overflowY + (s.transform !== 'none' ? ' tf=' + s.transform : '') +
              (s.touchAction !== 'auto' ? ' ta=' + s.touchAction : '') + (s.scrollSnapType !== 'none' ? ' snap=' + s.scrollSnapType : '') +
              (s.backgroundImage !== 'none' ? ' bgimg' : '') + ' sw=' + e.scrollWidth + ' sl=' + Math.round(e.scrollLeft);
          }
          var out = ['path=' + location.pathname + ' vw=' + innerWidth + 'x' + innerHeight];
          var imgs = Array.prototype.filter.call(document.querySelectorAll('img'), function (im) {
            var r = im.getBoundingClientRect(); return r.width > innerWidth * 0.4 && r.height > 100;
          });
          out.push('big images: ' + imgs.length);
          imgs.slice(0, 6).forEach(function (im, k) { out.push(' IMG' + k + ' ' + R(im) + ' nat=' + im.naturalWidth + 'x' + im.naturalHeight + ' src=' + (im.currentSrc || im.src).split('?')[0].slice(-30)); });
          if (imgs[0]) {
            out.push('chain from IMG0 up:');
            var e = imgs[0], n = 0;
            while (e && e !== document.body && n < 14) {
              out.push(' ' + tag(e) + ' ' + R(e) + ' kids=' + e.children.length + ' ' + sty(e));
              e = e.parentElement; n++;
            }
            // Siblings at the level where the images diverge (the track).
            if (imgs[1]) {
              var a = imgs[0], common = null;
              for (var x = a; x; x = x.parentElement) if (x.contains(imgs[1])) { common = x; break; }
              if (common) {
                out.push('common parent of IMG0/IMG1: ' + tag(common) + ' ' + R(common) + ' ' + sty(common));
                Array.prototype.slice.call(common.children, 0, 8).forEach(function (k, i) { out.push('  child' + i + ' ' + tag(k) + ' ' + R(k) + ' ' + sty(k)); });
              }
            }
          }
          window.__omSnap = { found: imgs.length, done: 0, skipped: [], dump: out.join('\n') };

          // FIX (per the 0.6.4 report): the viewer/listing photo strip is a native horizontal
          // scroller (overflow-x:auto) whose slides are already exactly one strip wide. It just
          // has no snapping. Add snapping and NOTHING else — no size or image changes (those
          // broke the viewer last time). Only for scrollers where every slide is full width and
          // holds an image, so chip rows and other carousels are untouched.
          function snap(sc) {
            if (sc.getAttribute('data-om-snap')) return;
            var w = sc.clientWidth, kids = sc.children;
            if (w < 200 || kids.length < 2) return;
            for (var i = 0; i < kids.length; i++) {
              var kw = kids[i].getBoundingClientRect().width;
              if (Math.abs(kw - w) > 3 || !kids[i].querySelector('img')) return;
            }
            sc.setAttribute('data-om-snap', '1');
            sc.style.setProperty('scroll-snap-type', 'x mandatory', 'important');
            for (var j = 0; j < kids.length; j++) {
              kids[j].style.setProperty('scroll-snap-align', 'start', 'important');
              kids[j].style.setProperty('scroll-snap-stop', 'always', 'important');
            }
            window.__omSnap.done++;
          }
          function snapAll() {
            var els = document.querySelectorAll('div');
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (el.scrollWidth <= el.clientWidth + 20) continue;
              var ox = getComputedStyle(el).overflowX;
              if (ox === 'auto' || ox === 'scroll') snap(el);
            }
          }
          snapAll();
          if (!window.__omSnapObs) {
            var timer = 0;
            window.__omSnapObs = new MutationObserver(function () {
              if (timer) return;
              timer = setTimeout(function () {
                timer = 0;
                // Listing and photo pages only (the viewer overlay sets the address to "/").
                if (/\/marketplace\/item|\/photo|\/media|^\/$/.test(location.pathname)) snapAll();
              }, 400);
            });
            window.__omSnapObs.observe(document.documentElement, { childList: true, subtree: true });
          }
        })();
    """.trimIndent()

    /**
     * Pinch zoom in the photo viewer: loosen Facebook's viewport tag (which forbids zooming) and
     * let pinch gestures through elements that block them with touch-action. Turned off again,
     * the tag is restored, which also snaps the page back to 1x.
     */
    fun zoomJs(on: Boolean) = """
        (function () {
          var m = document.querySelector('meta[name=viewport]');
          if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); }
          if (m.getAttribute('data-om-orig') === null) m.setAttribute('data-om-orig', m.content || 'width=device-width, initial-scale=1');
          var st = document.getElementById('om-zoom');
          if (${on}) {
            m.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes';
            if (!st) { st = document.createElement('style'); st.id = 'om-zoom'; (document.head || document.documentElement).appendChild(st); }
            // Not zoomed: sideways swipes and pinches only, so a slightly diagonal swipe still
            // changes photo instead of becoming an up/down drag. Zoomed in: drag any direction
            // to look around the photo.
            window.__omZoomOn = true;
            var upd = window.__omZoomUpd = window.__omZoomUpd || function () {
              var s = document.getElementById('om-zoom'); if (!s || !window.__omZoomOn) return;
              var zoomed = window.visualViewport && visualViewport.scale > 1.02;
              s.textContent = '.hscroller, .hscroller * { touch-action: ' + (zoomed ? 'pan-x pan-y pinch-zoom' : 'pan-x pinch-zoom') + ' !important; }';
            };
            if (window.visualViewport && !window.__omZoomListen) { window.__omZoomListen = true; visualViewport.addEventListener('resize', upd); }
            upd();
          } else {
            window.__omZoomOn = false;
            m.content = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no';
            setTimeout(function () { m.content = m.getAttribute('data-om-orig'); }, 300);
            if (st) st.textContent = '';
          }
        })();
    """.trimIndent()

    /** Shows the bottom event log. Off for normal use. */
    const val DEBUG_TOASTS = false

    /**
     * Scroll-performance report (long-press the Marketplace header to copy it). Separates the
     * app's own work (scan time) from Facebook's fetch latency (bottom → new listings).
     */
    val PERF_JS = """
        (function () {
          var s = window.__omStats; if (!s) return 'no stats (cleanup not running on this page)';
          var b = s.batches, avg = b.length ? Math.round(b.reduce(function (a, x) { return a + x; }, 0) / b.length) : 0;
          var res = performance.getEntriesByType('resource');
          var xhr = res.filter(function (r) { return r.initiatorType === 'xmlhttprequest' || r.initiatorType === 'fetch'; });
          var slow = xhr.filter(function (r) { return r.duration > 1000; }).length;
          var xavg = xhr.length ? Math.round(xhr.reduce(function (a, r) { return a + r.duration; }, 0) / xhr.length) : 0;
          var imgs = res.filter(function (r) { return r.initiatorType === 'img'; });
          var iavg = imgs.length ? Math.round(imgs.reduce(function (a, r) { return a + r.duration; }, 0) / imgs.length) : 0;
          var mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) + 'MB' : 'n/a';
          return 'APP WORK: scans=' + s.scans + ' total=' + Math.round(s.scanMs) + 'ms max=' + Math.round(s.maxScanMs) + 'ms nodes=' + s.nodesSeen +
            '\nLISTING BATCHES (bottom → arrival, ms): ' + (b.length ? b.join(', ') + ' avg=' + avg : 'none recorded') +
            '\nNETWORK: xhr/fetch=' + xhr.length + ' avg=' + xavg + 'ms slow(>1s)=' + slow + ' | images=' + imgs.length + ' avg=' + iavg + 'ms' +
            '\nPAGE: elements=' + document.querySelectorAll('*').length + ' height=' + document.documentElement.scrollHeight + ' heap=' + mem +
            '\nSLOWEST REQUESTS: ' + xhr.slice().sort(function (a, c) { return c.duration - a.duration; }).slice(0, 5).map(function (r) { return Math.round(r.duration) + 'ms ' + r.name.replace(/^https?:\/\/[^/]+/, '').slice(0, 50); }).join(' | ');
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

    /** Set by the loop guard: while now < this, the root path is allowed regardless of state. */
    @Volatile var allowRootUntil = 0L

    fun isAllowed(uri: Uri): Boolean {
        val path = uri.path.orEmpty().ifEmpty { "/" }
        if (path == "/" || path == "/home.php") {
            // Logged-out facebook.com/ IS the login page. "/?next=..." is Facebook's login
            // handoff (never the feed). And a half-logged-in session (checkpoint pending) has
            // the login cookie but still gets sent here, so the loop guard can force-allow it.
            if (!isLoggedIn()) return true
            if (uri.getQueryParameter("next") != null) return true
            if (System.currentTimeMillis() < allowRootUntil) return true
        }
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
                // Blank it but keep its space (the title overlaps the row with a negative margin),
                // then slide the title left over the empty slot. Transform doesn't touch layout.
                Array.prototype.forEach.call(tl.children, function (row) {
                  if (row.getAttribute('role') === 'tab') return;
                  var back = row.querySelector('[role="button"]');
                  if (!back || back.getBoundingClientRect().left >= 60) return;
                  back.style.setProperty('visibility', 'hidden', 'important');
                  back.style.setProperty('pointer-events', 'none', 'important');
                  var h1 = row.querySelector('h1');
                  if (!h1) return;
                  // The title's own column: the child of the row that contains the H1.
                  var col = h1;
                  while (col.parentElement && col.parentElement !== row) col = col.parentElement;
                  // Line the title up with the page's normal left margin (where the chip row
                  // starts, ~14px), then enlarge it 20%. Both are transforms, so the header's
                  // layout doesn't move.
                  var target = 14;
                  var shift = Math.round(h1.getBoundingClientRect().left - target);
                  if (shift > 0 && shift < 100) col.style.setProperty('transform', 'translateX(-' + shift + 'px)', 'important');
                  h1.style.setProperty('transform-origin', 'left center', 'important');
                  h1.style.setProperty('transform', 'scale(1.2)', 'important');
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
          // Whole sections headed "Suggested buy and sell groups" / "Suggested for you" (ads):
          // from the heading, climb and hide the biggest full-width block that (a) starts at the
          // heading, so nothing above it (the listing itself) goes, and (b) holds no links to
          // real Marketplace listings, so the listings grid never goes. (Ads can show prices
          // like "Save $50", so prices can't tell ads from listings; listing links can.)
          // "New for you" (notifications block at the top of Marketplace) goes too. Its card can
          // link to a listing, so for it only the CA$ check guards the listings grid below.
          var SECTION = /^(Suggested buy and sell groups|Suggested for you|Sponsored|Ads|New for you)$/i;
          function hideSection(heading) {
            var top = heading.getBoundingClientRect().top, el = heading, best = null;
            var notif = /^New for you$/i.test(heading.textContent.trim());
            while (el && el !== document.body) {
              var r = el.getBoundingClientRect();
              if (r.top < top - 30) break;
              if (!notif && el.querySelector('a[href*="/marketplace/item/"]')) break;
              if (/CA\$/.test(el.innerText)) break; // listing prices read "CA$…"; ads say "$50"
              if (r.width >= innerWidth * 0.9 && r.height > 40) best = el;
              el = el.parentElement;
            }
            if (best) hide(best);
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
                else if (t.length < 40 && SECTION.test(t)) hideSection(el);
              }
            }
          }
          // Timing: how long each scan takes, and how long between reaching the bottom of the
          // page and new listings appearing. Read out via the clipboard report.
          var stats = window.__omStats = { scans: 0, scanMs: 0, maxScanMs: 0, batches: [], lastBottomAt: 0, nodesSeen: 0 };
          var scanRaw = scan;
          scan = function (root) {
            var t0 = performance.now(); scanRaw(root); var dt = performance.now() - t0;
            stats.scans++; stats.scanMs += dt; if (dt > stats.maxScanMs) stats.maxScanMs = dt;
          };
          window.__omScan = scan;
          scan(document.body);

          // Never compete with scrolling: queue added nodes, and process them only once the
          // user has paused for a moment, in idle time where the browser offers it.
          var pending = [], lastScroll = 0;
          addEventListener('scroll', function () {
            lastScroll = Date.now();
            // Near the bottom → Facebook should start fetching more. Note the time so the gap
            // until new listings arrive can be measured.
            var se = document.scrollingElement || document.documentElement;
            if (se.scrollTop + innerHeight > se.scrollHeight - 600 && !stats.lastBottomAt) stats.lastBottomAt = Date.now();
          }, { passive: true, capture: true });
          function drain() {
            window.__omTimer = 0;
            if (!pending.length) return;
            if (Date.now() - lastScroll < 300) { window.__omTimer = setTimeout(drain, 300); return; }
            var batch = pending; pending = [];
            var run = function () { for (var i = 0; i < batch.length; i++) if (batch[i].isConnected) scan(batch[i]); };
            if (window.requestIdleCallback) requestIdleCallback(run, { timeout: 1500 }); else run();
          }
          new MutationObserver(function (records) {
            var big = 0;
            for (var r = 0; r < records.length; r++) {
              var added = records[r].addedNodes;
              for (var n = 0; n < added.length; n++) if (added[n].nodeType === 1) { pending.push(added[n]); stats.nodesSeen++; if (added[n].querySelectorAll('*').length > 30) big++; }
            }
            // A sizeable addition after we hit the bottom = a batch of listings arriving.
            if (big && stats.lastBottomAt) {
              stats.batches.push(Date.now() - stats.lastBottomAt);
              if (stats.batches.length > 20) stats.batches.shift();
              stats.lastBottomAt = 0;
            }
            if (pending.length && !window.__omTimer) window.__omTimer = setTimeout(drain, 150);
          }).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()
}
