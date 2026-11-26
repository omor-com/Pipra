package com.pipra.browser

/**
 * Generates local, offline Matte Black / Silver / Electric Blue HTML pages for:
 * - Pipra Start Page (pipra://home)
 * - Custom Website Blocking (with 7–365 day lock details)
 * - Adult Website Protection
 * - Private Window landing screen
 */
object BlockedPageHtml {

    fun blockedWebsiteHtml(
        blockedHost: String,
        rule: BlockedSiteRule,
        blocker: WebsiteBlocker
    ): String {
        val safeHost = Codec.htmlEncode(blockedHost)
        val safeRuleHost = Codec.htmlEncode(rule.host)
        val scopeLabel = if (rule.includeSubdomains) "Host &amp; all subdomains" else "Exact host only"
        val remaining = Codec.htmlEncode(blocker.formatRemaining(rule.lockUntilMillis))
        val unlockDate = Codec.htmlEncode(blocker.formatUnlockDate(rule.lockUntilMillis))
        val isLocked = rule.isLocked()
        val lockBadge = if (isLocked) "LOCKED RULE · $remaining" else "LOCK EXPIRED · EDITABLE IN SETTINGS"

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Blocked by Pipra</title>
              <style>
                body {
                  margin: 0; padding: 28px 20px;
                  background: #0B0C0E; color: #E6E8EC;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: #14161A; border: 1px solid #2E333D;
                  border-radius: 16px; padding: 24px;
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(59,130,246,0.14); color: #60A5FA;
                  border: 1px solid rgba(59,130,246,0.35);
                }
                h1 { font-size: 22px; margin: 16px 0 8px; color: #FFFFFF; }
                .host { font-family: monospace; font-size: 15px; color: #9CA3AF; word-break: break-all; }
                .meta {
                  margin-top: 18px; padding: 14px; border-radius: 12px;
                  background: #1A1D23; border: 1px solid #2A2F3A; font-size: 13px; color: #C8CDD5;
                }
                .row { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
                .k { color: #9CA3AF; }
                .v { color: #E6E8EC; font-weight: 600; text-align: right; }
                .actions { margin-top: 20px; display: flex; gap: 10px; flex-wrap: wrap; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: #3B82F6; color: #FFFFFF; }
                .secondary { background: #22262E; color: #E6E8EC; border: 1px solid #343A46; }
                .note { margin-top: 16px; font-size: 12px; color: #9CA3AF; }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="pill">$lockBadge</span>
                <h1>Website Blocked</h1>
                <div class="host">$safeHost</div>
                <div class="meta">
                  <div class="row"><span class="k">Matched rule</span><span class="v">$safeRuleHost</span></div>
                  <div class="row"><span class="k">Scope</span><span class="v">$scopeLabel</span></div>
                  <div class="row"><span class="k">Lock period</span><span class="v">${rule.lockDays} days</span></div>
                  <div class="row"><span class="k">Unlock date</span><span class="v">$unlockDate</span></div>
                </div>
                <div class="actions">
                  <button class="primary" onclick="if(history.length>1){history.back()}else{location.href='pipra://home'}">Go Back</button>
                  <button class="secondary" onclick="location.href='pipra://home'">Pipra Home</button>
                </div>
                <p class="note">
                  ${if (isLocked) "Active Website Blocking rules cannot be edited or removed in Settings until their lock period expires." else "The lock period for this rule has expired. You can edit or remove it in Pipra Settings."}
                </p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun adultBlockedHtml(blockedHost: String): String {
        val safeHost = Codec.htmlEncode(blockedHost)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Adult Protection · Pipra</title>
              <style>
                body {
                  margin: 0; padding: 28px 20px;
                  background: #0B0C0E; color: #E6E8EC;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: #14161A; border: 1px solid #2E333D;
                  border-radius: 16px; padding: 24px;
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(59,130,246,0.14); color: #60A5FA;
                  border: 1px solid rgba(59,130,246,0.35);
                }
                h1 { font-size: 22px; margin: 16px 0 8px; color: #FFFFFF; }
                .host { font-family: monospace; font-size: 15px; color: #9CA3AF; word-break: break-all; }
                p { font-size: 14px; color: #C8CDD5; margin-top: 14px; }
                .actions { margin-top: 20px; display: flex; gap: 10px; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: #3B82F6; color: #FFFFFF; }
                .secondary { background: #22262E; color: #E6E8EC; border: 1px solid #343A46; }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="pill">Adult Protection Active</span>
                <h1>Domain Blocked Locally</h1>
                <div class="host">$safeHost</div>
                <p>
                  Pipra Adult Protection blocked this destination using your locally cached domain rules.
                  No URLs or browsing history were transmitted to any remote service.
                </p>
                <div class="actions">
                  <button class="primary" onclick="if(history.length>1){history.back()}else{location.href='pipra://home'}">Go Back</button>
                  <button class="secondary" onclick="location.href='pipra://home'">Pipra Home</button>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun pipraHomeHtml(
        adBlockEnabled: Boolean,
        adBlockMode: AdBlockMode,
        totalBlocked: Long,
        lockedRuleCount: Int,
        adultProtectionEnabled: Boolean,
        bookmarks: List<Bookmark>
    ): String {
        val bookmarkItems = if (bookmarks.isEmpty()) {
            "<div class='empty'>No bookmarks yet. Tap the star in the menu to save sites locally.</div>"
        } else {
            bookmarks.take(8).joinToString("") { b ->
                val title = Codec.htmlEncode(b.title.ifBlank { b.url }.take(36))
                val url = Codec.htmlEncode(b.url)
                val host = Codec.htmlEncode(DomainUtils.extractHostFromUrl(b.url) ?: b.url)
                "<a class='bm' href='$url'><div class='bm-title'>$title</div><div class='bm-host'>$host</div></a>"
            }
        }
        val shieldsLabel = if (adBlockEnabled) "${adBlockMode.label} · $totalBlocked blocked" else "Paused"
        val adultLabel = if (adultProtectionEnabled) "Active" else "Off"

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Pipra</title>
              <style>
                body {
                  margin: 0; padding: 24px 18px 40px;
                  background: #0B0C0E; color: #E6E8EC;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                .wrap { max-width: 560px; margin: 18px auto 0; }
                .brand { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
                .badge-icon {
                  width: 44px; height: 44px; border-radius: 12px;
                  background: #1A1D23; border: 1px solid #2E333D;
                  display: flex; align-items: center; justify-content: center;
                  font-weight: 800; color: #3B82F6; font-size: 18px;
                }
                h1 { margin: 0; font-size: 24px; letter-spacing: 0.03em; color: #FFFFFF; }
                .sub { font-size: 13px; color: #9CA3AF; margin-top: 2px; }
                .grid {
                  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;
                  margin-bottom: 22px;
                }
                .stat {
                  background: #14161A; border: 1px solid #2A2F3A;
                  border-radius: 12px; padding: 12px;
                }
                .stat-k { font-size: 11px; color: #9CA3AF; text-transform: uppercase; letter-spacing: 0.05em; }
                .stat-v { font-size: 13px; color: #E6E8EC; font-weight: 600; margin-top: 4px; }
                .accent { color: #60A5FA; }
                .sec-title { font-size: 12px; color: #9CA3AF; text-transform: uppercase; letter-spacing: 0.06em; margin: 18px 0 10px; }
                .bm-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
                .bm {
                  display: block; text-decoration: none;
                  background: #14161A; border: 1px solid #2A2F3A;
                  border-radius: 12px; padding: 12px;
                }
                .bm-title { font-size: 14px; font-weight: 600; color: #E6E8EC; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .bm-host { font-size: 12px; color: #9CA3AF; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .empty {
                  background: #14161A; border: 1px dashed #2E333D;
                  border-radius: 12px; padding: 16px; font-size: 13px; color: #9CA3AF;
                }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="brand">
                  <div class="badge-icon">P</div>
                  <div>
                    <h1>Pipra</h1>
                    <div class="sub">Private Android Web Browser · Offline-First Protection</div>
                  </div>
                </div>
                <div class="grid">
                  <div class="stat"><div class="stat-k">Ad Blocker</div><div class="stat-v accent">$shieldsLabel</div></div>
                  <div class="stat"><div class="stat-k">Site Locks</div><div class="stat-v">$lockedRuleCount rule(s)</div></div>
                  <div class="stat"><div class="stat-k">Adult Guard</div><div class="stat-v">$adultLabel</div></div>
                </div>
                <div class="sec-title">Bookmarks</div>
                ${if (bookmarks.isEmpty()) bookmarkItems else "<div class='bm-grid'>$bookmarkItems</div>"}
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun privateLandingHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Pipra Private</title>
          <style>
            body {
              margin: 0; padding: 28px 20px;
              background: #080A0F; color: #E6E8EC;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              line-height: 1.55;
            }
            .card {
              max-width: 520px; margin: 18px auto;
              background: #101520; border: 1px solid #2A3447;
              border-radius: 16px; padding: 22px;
            }
            h1 { font-size: 21px; color: #60A5FA; margin: 0 0 10px; }
            ul { padding-left: 18px; color: #C8CDD5; font-size: 14px; }
            li { margin: 8px 0; }
            .note {
              margin-top: 14px; font-size: 12px; color: #9CA3AF;
              border-top: 1px solid #242C3C; padding-top: 12px;
            }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Private Window</h1>
            <ul>
              <li>Runs in an isolated Android process (<code>:incognito</code>) with a separate cookie jar, cache, and site storage.</li>
              <li>Cookies, site data, cache, and tabs are wiped when you close this window and verified clean on the next private launch.</li>
              <li>Pages visited here are not saved to history, and screenshots/recents previews are blocked (<code>FLAG_SECURE</code>).</li>
              <li>Third-party cookies are always refused in private windows.</li>
              <li>Your Ad Blocker, locked Website Blocking rules, and Adult Protection remain enforced.</li>
            </ul>
            <div class="note">
              Note: Private windows prevent local device retention, but do not make your network traffic anonymous to websites, your ISP, or network administrators.
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()
}
