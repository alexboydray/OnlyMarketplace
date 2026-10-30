<p align="center">
  <img src="docs/banner.svg" alt="OnlyMarketplace" width="100%">
</p>

<p align="center">
  <a href="https://github.com/alexboydray/OnlyMarketplace/releases/latest/download/OnlyMarketplace.apk"><img alt="Download the APK" src="https://img.shields.io/badge/Download-APK-0866FF?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://github.com/alexboydray/OnlyMarketplace/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/alexboydray/OnlyMarketplace?style=for-the-badge&color=0866FF&label=Version"></a>
  <img alt="Android 9 or newer" src="https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
</p>

---

### Tired of opening Facebook to check Marketplace… and somehow losing half an hour to your feed and endless reels?

**Look no further.** OnlyMarketplace is a tiny Android app that opens straight into Facebook Marketplace and keeps you there. You get the listings, search, photos and seller messages, and nothing else: no news feed, no reels, no stories, no notifications pulling you away.

You open it, find the thing, message the seller, and you're done.

## ✨ What you get

| | |
|---|---|
| 🛍️ **Just Marketplace** | Browse, search, view listings and message sellers. The feed, reels, groups and every other part of Facebook are locked out; tapping your way there bounces you straight back. |
| 🚫 **No ads or filler** | "Suggested for you" ads, sponsored cards, suggested groups, "New for you" and the "Open app" nag are all hidden. |
| 🖼️ **Better photos** | Swipe one photo at a time, and pinch to zoom in on the details. |
| 🔄 **Easy refresh** | Pull down to refresh, or tap the **Marketplace** title to jump back to the home page. |
| 🔒 **Your login stays with Facebook** | You sign in on Facebook's own login page. The app never sees or stores your password. |
| 🪶 **Small and simple** | No trackers, no analytics, no accounts, no extra permissions. |

## 📲 Install

1. On your phone, tap **[Download the APK](https://github.com/alexboydray/OnlyMarketplace/releases/latest/download/OnlyMarketplace.apk)**.
2. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**.
3. Open **OnlyMarketplace** and log in with your Facebook account.

> [!TIP]
> **Updating:** just install the new APK over the top. Don't uninstall first, or you'll have to log in again.
>
> Want updates to arrive automatically? Add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) and it will let you know when a new version is out.

> [!NOTE]
> The first time you log in, Facebook may ask you to confirm it's you (a code, or a tap on another device). That's Facebook's normal security check for a new device and only happens once.

## ❓ FAQ

<details>
<summary><b>Is this safe? Can it see my password?</b></summary>

No. The login form is Facebook's own page, loaded straight from facebook.com. The app only keeps the same login cookie any browser would, so you stay signed in. The full source code is in this repository.
</details>

<details>
<summary><b>Why isn't it on the Google Play Store?</b></summary>

Google Play doesn't allow apps that show someone else's website without that website's permission, and this app shows Facebook. So it's shared here as a direct download instead.
</details>

<details>
<summary><b>Something looks broken after a while.</b></summary>

Facebook changes its pages from time to time, and that can occasionally break a hidden section or bring an ad back. Check for a newer version here, or [open an issue](https://github.com/alexboydray/OnlyMarketplace/issues) with a screenshot.
</details>

<details>
<summary><b>Can I still message buyers and sellers?</b></summary>

Yes. Chatting about a listing stays allowed.
</details>

## 🛠️ For developers

<details>
<summary>How it works, and how to build it</summary>

- It's a Kotlin WebView app pointed at `m.facebook.com/marketplace`.
- `Marketplace.kt` holds the rules: the allowed pages (Marketplace, login and security checks, Messenger for listing chats) and the page clean-up scripts (hiding the nav bar, ads and suggested sections; photo snapping).
- `MainActivity.kt` checks every navigation against those rules, including Facebook's in-page navigations, and bounces anything else back to Marketplace. Links to other sites open in your normal browser.

**Tweaks**

- Block Messenger too: set `ALLOW_MESSAGES = false` in `Marketplace.kt`.
- Allow or block pages: edit `ALLOWED_PREFIXES`.
- Hide more of the page: extend `HIDE_CHROME_JS`.

**Building:** no local Android SDK is needed. Every push to `main` is built by GitHub Actions and published to the *Latest build* pre-release, signed with the project's permanent key so updates install over the top.
</details>

---

<sub>OnlyMarketplace is an independent project and is not affiliated with, endorsed by, or sponsored by Meta Platforms, Inc. Facebook and Marketplace are trademarks of Meta Platforms, Inc.</sub>
