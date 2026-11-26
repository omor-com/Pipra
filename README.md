# Pipra — Private Android Web Browser

**Application ID:** `com.pipra.browser.app`  
**Developer:** Muhammad Omor  
**Derived From:** [Injectra](https://github.com/n0obcoders/Injectra.git)  

Pipra is a matte-black and silver privacy-focused Android browser built with Kotlin, Android WebView, and Jetpack Compose Material 3.

---

## Implemented Features

### Core Browser
- Multi-tab browsing (`MainActivity`) with optional tab strip, URL/search omnibox with history/bookmark suggestions, back/forward long-press navigation history, find-in-page, desktop site spoofing (`DESKTOP_SPOOF_JS`), fullscreen video, file uploads, downloads (`DownloadManager` with sanitized filenames and http/https scheme validation), and share/copy link actions.
- Isolated Private Windows (`PrivateActivity` running in `:incognito` process with `FLAG_SECURE`, third-party cookies blocked, and ephemeral cookies/storage/cache wiped on close and launch).
- Cookie Manager (`CookieIO.kt` / `BrowserCookieDialogs.kt`) supporting JSON and Netscape `cookies.txt` import/export and per-site/global clearing.
- Developer Workbench (`DevTools.kt`, `RuleEngine.kt`, `Scanner.kt`, `Workbench.kt`, `BrowserDevDialogs.kt`) with header/request rules, HTTP history & HAR/cURL export, Repeater, Passive Scanner, Storage Editor, View Source, Encoder/Decoder, Comparer, and Sequencer — all gated so DevTools redirects/requests cannot bypass Website Blocking or Adult Protection.

### Tiered Ad Blocker (`AdBlockerEngine.kt`)
- Master On/Off switch (`Store.shieldsEnabled`), per-site exceptions, accurate blocked-request statistics, and zero browsing-activity logging.
- Three genuinely distinct filtering tiers (`AdBlockMode`):
  - **Medium:** Core ad exchanges and major third-party trackers (conservative).
  - **High:** Adds telemetry, analytics, attribution, fingerprinting domains, cached external lists, and third-party ad host/path heuristics.
  - **Ultimate:** Adds crypto-miner/popunder/malvertising domains, full cached external lists, aggressive URL path heuristics, tracking query parameter stripping, and cosmetic CSS element hiding.
- Off-main-thread manual filter-list updates with validation before replacing the local cache; offline blocking uses the cached ruleset if an update fails.

### Website Blocking with 7–365 Day Lock (`WebsiteBlocker.kt`)
- Supports unlimited independent hostname/URL rules (`DomainUtils.normalizeHost`) with exact-host or include-subdomains scope and zero partial-domain false positives (e.g., `example.com` never matches `notexample.com`).
- Enforces mandatory 7–365 day locks at the engine layer: active locked rules cannot be edited or deleted in Settings until their deadline expires.
- Rules and deadlines are persisted atomically with HMAC verification across restarts and across both normal and `:incognito` processes.
- Enforced across omnibox entry, clicked links, HTTP/DevTools redirects, and subresource requests, rendering a local offline blocked-page screen (`BlockedPageHtml.kt`).

### Adult Website Protection (`AdultBlockerEngine.kt`)
- Dedicated toggle and section in Settings with a bundled offline baseline (`res/raw/adult_domains_baseline.txt`) plus manual updates from public hosts lists validated and cached locally.
- Zero remote URL classification or browsing-history uploads.

---

## Build & CI

- Local build & unit tests:
  ```bash
  gradle :app:testDebugUnitTest
  gradle :app:assembleDebug
  ```
- See [`SIGNING_AND_CI.md`](./SIGNING_AND_CI.md) for keystore generation, GitHub Secrets setup, and automated APK builds via `.github/workflows/android-build.yml`.
