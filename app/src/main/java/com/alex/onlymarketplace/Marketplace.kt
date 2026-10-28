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

    /** Set false to also block chatting with buyers/sellers via Messenger. */
    private const val ALLOW_MESSAGES = true

    private val FACEBOOK_HOSTS = listOf("facebook.com", "fb.com", "fbcdn.net", "fbsbx.com")

    /** Path prefixes that may load. Everything else on facebook.com bounces to [HOME]. */
    private val ALLOWED_PREFIXES = listOf(
        "/marketplace",
        // Login, 2FA, account checkpoints, password recovery
        "/login", "/checkpoint", "/two_step_verification", "/recover", "/auth", "/x/",
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
