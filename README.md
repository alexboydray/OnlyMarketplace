# Only Marketplace

An Android app that is Facebook Marketplace and nothing else.

It's a WebView pointed at `facebook.com/marketplace`. You log in on Facebook's own
login page inside the app — the app never sees or stores your password; the session
cookie lives in the WebView's cookie jar like any browser.

## How the lock works

- `Marketplace.kt` holds the rules: allowed path prefixes (`/marketplace`, login/2FA,
  consent pages, logout, and optionally Messenger for buyer/seller chat).
- `MainActivity.kt` checks every navigation against those rules — both real page loads
  and Facebook's in-page (pushState) navigations — and bounces anything else back to
  Marketplace. Off-site links open in the system browser.
- A small CSS snippet hides the Home / Video / Groups / Notifications shortcuts so they
  aren't a constant temptation. That part is cosmetic and may need tweaking when
  Facebook changes its layout; the navigation lock is what actually enforces the rule.

## Tweaks

- Block Messenger too: set `ALLOW_MESSAGES = false` in `Marketplace.kt`.
- Add/remove allowed pages: edit `ALLOWED_PREFIXES`.
- Hide more UI: add selectors to `HIDE_CHROME_JS`.

## Building

No local SDK needed. Push to `main` and GitHub Actions builds the APK and attaches it to
the "Latest build" release (same setup as SteamShowcase). Debug-signed, so it sideloads
as-is.

## Known limits

- Facebook may occasionally show "unsupported browser" or an extra security check. The
  app strips the WebView markers from the user agent to avoid that; if it recurs, update
  the user-agent handling in `MainActivity.kt`.
- Personal-use wrapper. Not affiliated with Meta.
