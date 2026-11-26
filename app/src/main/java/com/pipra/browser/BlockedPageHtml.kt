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
                val title = Codec.htmlEncode(b.title.ifBlank { b.url }.take(32))
                val url = Codec.htmlEncode(b.url)
                val host = Codec.htmlEncode(DomainUtils.extractHostFromUrl(b.url) ?: b.url)
                val initial = host.take(1).uppercase()
                """
                <a class='shortcut' href='$url'>
                  <div class='shortcut-icon'>$initial</div>
                  <div class='shortcut-label'>$title</div>
                </a>
                """.trimIndent()
            }
        }
        val shieldsLabel = if (adBlockEnabled) "${adBlockMode.label} · $totalBlocked blocked" else "Paused"
        val adultLabel = if (adultProtectionEnabled) "Active (Default)" else "Off"

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <meta name="theme-color" content="#030303">
            <title>Pipra Browser</title>
            <style>
            :root{color-scheme:dark;--red:#3B82F6;--bg:#030303;--text:#f7f7f8;--muted:#aeb0b6;--panel:#14161A;--border:#2E333D}
            *{box-sizing:border-box}
            html,body{margin:0;width:100%;min-height:100%;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
            body{min-height:100svh;overflow-x:hidden}
            .page{position:relative;isolation:isolate;min-height:100svh;display:flex;flex-direction:column;align-items:center;padding:32px 16px 60px;background:radial-gradient(ellipse at 50% 35%,#121620 0%,#030303 65%,#000 100%)}
            .brand{display:flex;flex-direction:column;align-items:center;margin:24px 0 28px;text-align:center}
            .ant-logo{width:84px;height:84px;border-radius:24px;background:#101319;border:1px solid var(--border);display:grid;place-items:center;box-shadow:0 8px 24px rgba(59,130,246,0.15);margin-bottom:12px;font-size:36px;font-weight:800;color:#60A5FA}
            h1{margin:0;font-size:26px;font-weight:700;letter-spacing:0.04em;color:#FFFFFF}
            .sub{font-size:13px;color:var(--muted);margin-top:4px}
            
            .search-box{width:100%;max-width:620px;height:56px;display:flex;align-items:center;gap:12px;padding:0 12px 0 16px;border-radius:999px;border:1px solid var(--border);background:linear-gradient(135deg,rgba(25,28,34,.95),rgba(15,18,22,.97));box-shadow:0 8px 32px rgba(0,0,0,0.3);margin-bottom:28px}
            .search-box input{min-width:0;flex:1;background:transparent;border:0;outline:0;color:#f7f7f8;font-size:16px;font-family:inherit}
            .search-box input::placeholder{color:var(--muted)}
            .search-btn{width:40px;height:40px;border:0;border-radius:50%;background:#3B82F6;color:white;display:grid;place-items:center;cursor:pointer;font-weight:bold}
            
            .stats-grid{width:100%;max-width:620px;display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:28px}
            .stat{background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:12px;text-align:center}
            .stat-k{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:0.06em}
            .stat-v{font-size:13px;color:#E6E8EC;font-weight:600;margin-top:4px}
            .accent{color:#60A5FA}
            
            .section-title{width:100%;max-width:620px;font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:0.08em;margin:0 0 12px}
            .shortcuts-grid{width:100%;max-width:620px;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}
            .shortcut{display:flex;flex-direction:column;align-items:center;gap:8px;color:#eee;text-decoration:none;font-size:12px;text-align:center}
            .shortcut-icon{width:56px;height:56px;border-radius:18px;background:var(--panel);border:1px solid var(--border);display:grid;place-items:center;font-size:20px;font-weight:600;color:#60A5FA;box-shadow:0 4px 12px rgba(0,0,0,0.2)}
            .shortcut-label{width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#C8CDD5;font-size:12px}
            .empty{width:100%;max-width:620px;background:var(--panel);border:1px dashed var(--border);border-radius:14px;padding:20px;text-align:center;color:var(--muted);font-size:13px}
            </style>
            </head>
            <body>
            <div class="page">
              <div class="brand">
                <div class="ant-logo">🐜</div>
                <h1>Pipra</h1>
                <div class="sub">Private Android Web Browser · Chrome UI & Privacy Engine</div>
              </div>
              
              <form class="search-box" action="https://www.google.com/search" method="GET" onsubmit="event.preventDefault(); var q=this.q.value; if(q){location.href=(q.startsWith('http://')||q.startsWith('https://')||q.includes('.'))?(q.includes('://')?q:'https://'+q):'https://www.google.com/search?q='+encodeURIComponent(q)}">
                <input type="text" name="q" placeholder="Search with Google or type URL..." autocomplete="off" autofocus>
                <button type="submit" class="search-btn" aria-label="Search">🔍</button>
              </form>
              
              <div class="stats-grid">
                <div class="stat"><div class="stat-k">Ad Blocker</div><div class="stat-v accent">$shieldsLabel</div></div>
                <div class="stat"><div class="stat-k">Site Locks</div><div class="stat-v">$lockedRuleCount rules</div></div>
                <div class="stat"><div class="stat-k">Adult Guard</div><div class="stat-v accent">$adultLabel</div></div>
              </div>
              
              <div class="section-title">Shortcuts & Bookmarks</div>
              <div class="${if (bookmarks.isEmpty()) "" else "shortcuts-grid"}">
                $bookmarkItems
              </div>
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
