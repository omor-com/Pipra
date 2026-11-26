# Security Summary

**Reviewed Files**: 26
**Issues Resolved**: 6

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| Plaintext Backup Exposure | `app/src/main/AndroidManifest.xml` | 10 | Android Auto-Backup defaults could allow device backups to copy cookies, browsing history, or blocking rule files off-device. | Medium | Fixed | Set `android:allowBackup="false"` and `android:fullBackupContent="false"` in `AndroidManifest.xml`, and constrained `backup_rules.xml` / `data_extraction_rules.xml`. |
| Unsafe Omnibox & Download Schemes | `app/src/main/java/com/pipra/browser/DomainUtils.kt` | 115 | Unchecked `javascript:`, `file:`, `content:`, or `data:` address-bar entries or crafted `Content-Disposition` filenames could execute scripts or traverse download paths. | High | Fixed | Treated non-`http(s)` schemes as search text in `DomainUtils.resolveOmniboxInput` and scrubbed path separators/`..` in `sanitizeDownloadFileName`. |
| DevTools Redirect & Fetch Bypass | `app/src/main/java/com/pipra/browser/RuleEngine.kt` | 100 | Request rewrite/redirect rules, HTTP 3xx redirects, or Repeater/Scanner fetches could bypass Website Blocking or Adult Protection rules. | High | Fixed | Wired `isBlockedDestination` into `RuleEngine.intercept`, manual HTTP redirect loop, and `BrowserDevDialogs` Scanner/Repeater checks. |
| Blocked URL History Leakage | `app/src/main/java/com/pipra/browser/MainActivity.kt` | 405 | An aborted navigation to a blocked website could still be recorded into local browsing history if `onPageFinished` fired for that URL. | Low | Fixed | Guarded `store.addHistory` with `!isBlockedDestination(pageUrl)`. |
| Cross-Process Policy & Lock Tampering | `app/src/main/java/com/pipra/browser/WebsiteBlocker.kt` | 220 | Separate `:incognito` process could miss updated locks or settings written only to single-process `SharedPreferences`, and unsigned rule files could be altered silently. | Medium | Fixed | Persisted `pipra_policy.json` and `pipra_blocked_sites.json` atomically with `lastModified()` invalidation and HMAC-SHA256 verification. |
| Filter List Overwrite on Bad Response | `app/src/main/java/com/pipra/browser/AdBlockerEngine.kt` | 289 | Network portals or HTTP error HTML pages returned during a filter update could wipe out working offline ad/adult blocking lists. | Medium | Fixed | Validated HTTPS-only sources, rejected HTML payloads, required minimum parsed domain counts, and kept the previous local cache untouched on any failure. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| TLS Certificate Validation | `app/src/main/java/com/pipra/browser/MainActivity.kt` | `onReceivedSslError` unconditionally calls `handler.cancel()` and never allows users to proceed past invalid certificates. |
| WebView File & Bridge Isolation | `app/src/main/java/com/pipra/browser/MainActivity.kt` | Disables `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, and `allowUniversalAccessFromFileURLs`, enables Safe Browsing, and never registers `addJavascriptInterface`. |
| Private Window Isolation | `app/src/main/java/com/pipra/browser/PrivateActivity.kt` | Runs in isolated `:incognito` process with `FLAG_SECURE`, third-party cookies disabled, zero history persistence, and cookie/storage wipe on launch and exit. |
| Least-Privilege Permissions | `app/src/main/AndroidManifest.xml` | Declares only `INTERNET` and `ACCESS_NETWORK_STATE`; omits broad storage, camera, microphone, and location permissions. |
| External Intent Hardening | `app/src/main/java/com/pipra/browser/MainActivity.kt` | Strips `component` and `selector`, enforces `CATEGORY_BROWSABLE`, requires user gesture, and asks explicit user confirmation before launching external apps. |

## PoC Verification

### DevTools Redirect & Unsafe Omnibox Bypass Prevention

#### Summary
| Field | Value |
|---|---|
| Issue Type | Blocking Rule Bypass & Unsafe URI Handling |
| Impact | High |
| Affected File | `app/src/main/java/com/pipra/browser/RuleEngine.kt:102` & `DomainUtils.kt:129` |
| Test Scenario | A user configures a DevTools `ACTION_REDIRECT` rule from `allowed-source.example.com` to `https://sub.blocked-target.example.com/payload.js` (while `blocked-target.example.com` is locked), or types `javascript:alert(1)` in the omnibox. |

#### How It Was Fixed
`RuleEngine.intercept` and `RuleEngine.runFetch` evaluate `isBlockedDestination` on rewritten targets and every HTTP `3xx` `Location` redirect hop before issuing network calls, returning an empty `WebResourceResponse` when matched. `DomainUtils.resolveOmniboxInput` intercepts non-`http(s)` schemes and routes them safely to search queries.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Test input arrives at `RuleEngine.intercept` (`RuleEngine.kt:56`) or `DomainUtils.resolveOmniboxInput` (`DomainUtils.kt:115`) | Input enters handler |
| 2 | `RuleEngine.kt:102` and `RuleEngine.kt:144` call `isBlockedDestination(fetchUrl)` -> `WebsiteBlocker.findMatchingRuleForUrl`; `DomainUtils.kt:134` checks `beforeColon in UNSAFE_SCHEMES` | Blocked domain detected / unsafe scheme caught |
| 3 | `RuleEngine` returns `emptyResponse()` without calling `OkHttpClient`; `DomainUtils` returns `searchTemplate + Uri.encode(t)` | **Handled Safely** |

#### Conclusion
**Fix Verified** — Redirect rules, HTTP redirects, and DevTools requests cannot fetch domains protected by Website Blocking or Adult Protection, and typed unsafe schemes cannot execute in the WebView.
