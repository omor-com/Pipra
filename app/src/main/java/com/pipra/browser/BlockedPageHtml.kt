package com.pipra.browser

/**
 * Generates local, offline Light & Dark theme HTML pages for:
 * - Pipra Start Page (pipra://home)
 * - Custom Website Blocking (with 7–365 day lock details)
 * - Adult Website Protection
 * - Offline & Network Error Page
 * - Private Window landing screen
 */
object BlockedPageHtml {

    fun blockedWebsiteHtml(
        blockedHost: String,
        rule: BlockedSiteRule,
        blocker: WebsiteBlocker,
        isDark: Boolean = false
    ): String {
        val safeHost = Codec.htmlEncode(blockedHost)
        val safeRuleHost = Codec.htmlEncode(rule.host)
        val scopeLabel = if (rule.includeSubdomains) "Host &amp; all subdomains" else "Exact host only"
        val remaining = Codec.htmlEncode(blocker.formatRemaining(rule.lockUntilMillis))
        val unlockDate = Codec.htmlEncode(blocker.formatUnlockDate(rule.lockUntilMillis))
        val isLocked = rule.isLocked()
        val lockBadge = if (isLocked) "LOCKED RULE · $remaining" else "LOCK EXPIRED · EDITABLE IN SETTINGS"

        val bg = if (isDark) "#0B0C0E" else "#F4F6F9"
        val card = if (isDark) "#14161A" else "#FFFFFF"
        val meta = if (isDark) "#1A1D23" else "#F8FAFC"
        val border = if (isDark) "#2E333D" else "#D8DFE8"
        val text = if (isDark) "#E6E8EC" else "#111827"
        val heading = if (isDark) "#FFFFFF" else "#0F172A"
        val muted = if (isDark) "#9CA3AF" else "#4B5563"
        val accent = if (isDark) "#3B82F6" else "#2563EB"
        val accentText = if (isDark) "#60A5FA" else "#1D4ED8"
        val secBg = if (isDark) "#22262E" else "#EEF2F6"

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
                  background: $bg; color: $text;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: $card; border: 1px solid $border;
                  border-radius: 16px; padding: 24px;
                  box-shadow: 0 8px 24px rgba(15,23,42,${if (isDark) "0.35" else "0.06"});
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(37,99,235,0.12); color: $accentText;
                  border: 1px solid rgba(37,99,235,0.32);
                }
                h1 { font-size: 22px; margin: 16px 0 8px; color: $heading; }
                .host { font-family: monospace; font-size: 15px; color: $muted; word-break: break-all; }
                .meta {
                  margin-top: 18px; padding: 14px; border-radius: 12px;
                  background: $meta; border: 1px solid $border; font-size: 13px; color: $text;
                }
                .row { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
                .k { color: $muted; }
                .v { color: $text; font-weight: 600; text-align: right; }
                .actions { margin-top: 20px; display: flex; gap: 10px; flex-wrap: wrap; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: $accent; color: #FFFFFF; }
                .secondary { background: $secBg; color: $text; border: 1px solid $border; }
                .note { margin-top: 16px; font-size: 12px; color: $muted; }
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

    fun adultBlockedHtml(blockedHost: String, isDark: Boolean = false): String {
        val safeHost = Codec.htmlEncode(blockedHost)
        val bg = if (isDark) "#0B0C0E" else "#F4F6F9"
        val card = if (isDark) "#14161A" else "#FFFFFF"
        val border = if (isDark) "#2E333D" else "#D8DFE8"
        val text = if (isDark) "#E6E8EC" else "#111827"
        val heading = if (isDark) "#FFFFFF" else "#0F172A"
        val muted = if (isDark) "#9CA3AF" else "#4B5563"
        val accent = if (isDark) "#3B82F6" else "#2563EB"
        val accentText = if (isDark) "#60A5FA" else "#1D4ED8"
        val secBg = if (isDark) "#22262E" else "#EEF2F6"

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
                  background: $bg; color: $text;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: $card; border: 1px solid $border;
                  border-radius: 16px; padding: 24px;
                  box-shadow: 0 8px 24px rgba(15,23,42,${if (isDark) "0.35" else "0.06"});
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(37,99,235,0.12); color: $accentText;
                  border: 1px solid rgba(37,99,235,0.32);
                }
                h1 { font-size: 22px; margin: 16px 0 8px; color: $heading; }
                .host { font-family: monospace; font-size: 15px; color: $muted; word-break: break-all; }
                p { font-size: 14px; color: $muted; margin-top: 14px; }
                .actions { margin-top: 20px; display: flex; gap: 10px; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: $accent; color: #FFFFFF; }
                .secondary { background: $secBg; color: $text; border: 1px solid $border; }
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

    fun networkErrorHtml(
        failedUrl: String,
        errorDescription: String,
        isOffline: Boolean,
        isDark: Boolean = false
    ): String {
        val safeUrl = Codec.htmlEncode(failedUrl)
        val safeDesc = Codec.htmlEncode(errorDescription.ifBlank { "Unable to reach the server" })
        val bg = if (isDark) "#0B0C0E" else "#F4F6F9"
        val card = if (isDark) "#14161A" else "#FFFFFF"
        val border = if (isDark) "#2E333D" else "#D8DFE8"
        val text = if (isDark) "#E6E8EC" else "#111827"
        val heading = if (isDark) "#FFFFFF" else "#0F172A"
        val muted = if (isDark) "#9CA3AF" else "#4B5563"
        val accent = if (isDark) "#3B82F6" else "#2563EB"
        val accentText = if (isDark) "#60A5FA" else "#1D4ED8"
        val secBg = if (isDark) "#22262E" else "#EEF2F6"

        val badge = if (isOffline) "NO INTERNET CONNECTION" else "PAGE LOAD ERROR"
        val title = if (isOffline) "You're Offline" else "Unable to Connect"
        val hint = if (isOffline) {
            "Check your Wi-Fi or mobile data connection, open saved offline bookmarks, or play Pipra Ant Dash while you wait."
        } else {
            "The server may be temporarily unreachable or the address could not be resolved ($safeDesc)."
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>$title · Pipra</title>
              <style>
                body {
                  margin: 0; padding: 28px 20px;
                  background: $bg; color: $text;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: $card; border: 1px solid $border;
                  border-radius: 18px; padding: 24px;
                  box-shadow: 0 8px 24px rgba(15,23,42,${if (isDark) "0.35" else "0.06"});
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(37,99,235,0.12); color: $accentText;
                  border: 1px solid rgba(37,99,235,0.32);
                }
                h1 { font-size: 22px; margin: 14px 0 6px; color: $heading; }
                .url { font-family: monospace; font-size: 13px; color: $muted; word-break: break-all; }
                p { font-size: 14px; color: $muted; margin-top: 14px; }
                .actions { margin-top: 22px; display: flex; gap: 10px; flex-wrap: wrap; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: $accent; color: #FFFFFF; }
                .secondary { background: $secBg; color: $text; border: 1px solid $border; }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="pill">$badge</span>
                <h1>$title</h1>
                <div class="url">$safeUrl</div>
                <p>$hint</p>
                <div class="actions">
                  <button class="primary" onclick="location.href='pipra://retry'">Retry</button>
                  <button class="secondary" onclick="location.href='pipra://home'">Pipra Home</button>
                  <button class="secondary" onclick="location.href='pipra://game'">Play Ant Dash</button>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun focusBlockedHtml(
        blockedHost: String,
        matchLabel: String,
        matchDetail: String,
        isDark: Boolean = false
    ): String {
        val safeHost = Codec.htmlEncode(blockedHost)
        val safeLabel = Codec.htmlEncode(matchLabel)
        val safeDetail = Codec.htmlEncode(matchDetail)
        val bg = if (isDark) "#0B0C0E" else "#F4F6F9"
        val card = if (isDark) "#14161A" else "#FFFFFF"
        val border = if (isDark) "#2E333D" else "#D8DFE8"
        val text = if (isDark) "#E6E8EC" else "#111827"
        val heading = if (isDark) "#FFFFFF" else "#0F172A"
        val muted = if (isDark) "#9CA3AF" else "#4B5563"
        val accent = if (isDark) "#3B82F6" else "#2563EB"
        val accentText = if (isDark) "#60A5FA" else "#1D4ED8"
        val secBg = if (isDark) "#22262E" else "#EEF2F6"

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Focus Mode · Pipra</title>
              <style>
                body {
                  margin: 0; padding: 28px 20px;
                  background: $bg; color: $text;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  line-height: 1.55;
                }
                .card {
                  max-width: 520px; margin: 28px auto;
                  background: $card; border: 1px solid $border;
                  border-radius: 16px; padding: 24px;
                  box-shadow: 0 8px 24px rgba(15,23,42,${if (isDark) "0.35" else "0.06"});
                }
                .pill {
                  display: inline-block; padding: 5px 11px;
                  border-radius: 999px; font-size: 11px; font-weight: 700;
                  letter-spacing: 0.06em; text-transform: uppercase;
                  background: rgba(37,99,235,0.12); color: $accentText;
                  border: 1px solid rgba(37,99,235,0.32);
                }
                h1 { font-size: 22px; margin: 16px 0 8px; color: $heading; }
                .host { font-family: monospace; font-size: 15px; color: $muted; word-break: break-all; }
                p { font-size: 14px; color: $muted; margin-top: 12px; }
                .actions { margin-top: 20px; display: flex; gap: 10px; flex-wrap: wrap; }
                button {
                  border: 0; border-radius: 10px; padding: 11px 16px;
                  font-size: 14px; font-weight: 600; cursor: pointer;
                }
                .primary { background: $accent; color: #FFFFFF; }
                .secondary { background: $secBg; color: $text; border: 1px solid $border; }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="pill">$safeLabel</span>
                <h1>Focus Window Active</h1>
                <div class="host">$safeHost</div>
                <p>Blocked by your Focus Schedule ($safeDetail). Stay focused — you can manage Focus rules or Pomodoro sessions in Pipra Focus Settings.</p>
                <div class="actions">
                  <button class="primary" onclick="location.href='pipra://home'">Pipra Home</button>
                  <button class="secondary" onclick="location.href='pipra://focus'">Focus Settings</button>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun pipraHomeHtml(
        activeEngine: String,
        availableEngines: List<String>,
        bookmarks: List<Bookmark>,
        isDark: Boolean = false,
        privacyTotalBlocked: Long = 0L,
        httpsUpgrades: Long = 0L,
        activeExtensionCount: Int = 0
    ): String {
        val bg = if (isDark) "#0B0C0E" else "#F4F6F9"
        val bgGrad = if (isDark) "radial-gradient(ellipse at 50% 25%, #121826 0%, #0B0C0E 68%)"
        else "radial-gradient(ellipse at 50% 20%, #FFFFFF 0%, #F4F6F9 72%)"
        val panel = if (isDark) "#14161A" else "#FFFFFF"
        val panelElevated = if (isDark) "#1D2129" else "#EEF2F6"
        val border = if (isDark) "#2E333D" else "#D8DFE8"
        val text = if (isDark) "#E6E8EC" else "#111827"
        val muted = if (isDark) "#9CA3AF" else "#4B5563"
        val accent = if (isDark) "#3B82F6" else "#2563EB"
        val accentSoft = if (isDark) "#60A5FA" else "#1D4ED8"
        val shadow = if (isDark) "rgba(0,0,0,0.40)" else "rgba(15,23,42,0.08)"

        val safeEngine = Codec.htmlEncode(activeEngine)
        val activeBadgeSvg = engineSvgBadge(activeEngine, accent)

        val engineOptionsHtml = buildString {
            availableEngines.forEach { engine ->
                val safe = Codec.htmlEncode(engine)
                val enc = Codec.htmlEncode(android.net.Uri.encode(engine))
                val isSelected = engine == activeEngine
                val iconSvg = engineSvgBadge(engine, accent)
                val activeClass = if (isSelected) " active" else ""
                append(
                    """
                    <button type="button" class="engine-opt$activeClass" onclick="location.href='pipra://engine?set=$enc'">
                      <span class="opt-left">$iconSvg<span>$safe</span></span>
                      <span class="opt-mark">${if (isSelected) "Active" else ""}</span>
                    </button>
                    """.trimIndent()
                )
            }
            append(
                """
                <button type="button" class="engine-opt custom-config" onclick="location.href='pipra://custom-engine'">
                  <span class="opt-left"><span class="eng-dot">⚙</span><span>Configure Custom Search URL…</span></span>
                  <span class="opt-mark">Edit</span>
                </button>
                """.trimIndent()
            )
        }

        val bookmarkSection = if (bookmarks.isEmpty()) {
            """
            <div class="empty">
              <div class="empty-title">No bookmarks saved yet</div>
              <div class="empty-sub">Tap the star in the browser menu to save favorite sites or offline pages here.</div>
            </div>
            """.trimIndent()
        } else {
            val cards = bookmarks.take(8).joinToString("") { b ->
                val title = Codec.htmlEncode(b.title.ifBlank { b.url }.take(28))
                val url = Codec.htmlEncode(b.url)
                val host = Codec.htmlEncode(DomainUtils.extractHostFromUrl(b.url) ?: b.url)
                val initial = host.take(1).uppercase()
                val offlinePill = if (b.hasOfflineCopy) "<span class='offline-tag'>Offline</span>" else ""
                """
                <a class="shortcut" href="$url">
                  <div class="shortcut-icon">$initial</div>
                  <div class="shortcut-label">$title</div>
                  $offlinePill
                </a>
                """.trimIndent()
            }
            "<div class='shortcuts-grid'>$cards</div>"
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <meta name="theme-color" content="$bg">
            <title>Pipra</title>
            <style>
            *{box-sizing:border-box}
            html,body{margin:0;width:100%;min-height:100%;background:$bg;color:$text;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
            body{min-height:100svh;overflow-x:hidden;background:$bgGrad}
            .page{min-height:100svh;display:flex;flex-direction:column;align-items:center;padding:28px 18px 48px}
            .brand{display:flex;flex-direction:column;align-items:center;margin:18px 0 26px;text-align:center}
            .ant-badge{
              width:72px;height:72px;border-radius:22px;
              background:$panel;border:1px solid $border;
              display:grid;place-items:center;
              box-shadow:0 8px 24px $shadow;margin-bottom:12px
            }
            h1{margin:0;font-size:26px;font-weight:700;letter-spacing:-0.01em;color:$text}
            .sub{font-size:13px;color:$muted;margin-top:4px}

            .search-wrap{position:relative;width:100%;max-width:600px;margin-bottom:20px}
            .search-box{
              width:100%;height:56px;display:flex;align-items:center;gap:8px;
              padding:0 8px 0 8px;border-radius:999px;
              border:1px solid $border;background:$panel;
              box-shadow:0 6px 24px $shadow
            }
            .engine-trigger{
              height:40px;display:flex;align-items:center;gap:6px;
              padding:0 10px 0 8px;border:1px solid $border;border-radius:999px;
              background:$panelElevated;color:$text;font-size:12px;font-weight:600;
              cursor:pointer;flex-shrink:0
            }
            .chev{font-size:10px;color:$muted}
            .search-box input{
              min-width:0;flex:1;background:transparent;border:0;outline:0;
              color:$text;font-size:15px;font-family:inherit
            }
            .search-box input::placeholder{color:$muted}
            .search-btn{
              width:40px;height:40px;border:0;border-radius:50%;
              background:$accent;color:#FFFFFF;display:grid;place-items:center;
              cursor:pointer;flex-shrink:0
            }

            .engine-menu{
              display:none;position:absolute;top:62px;left:0;width:min(320px,100%);
              background:$panel;border:1px solid $border;border-radius:16px;
              padding:8px;box-shadow:0 14px 36px $shadow;z-index:20
            }
            .engine-menu.open{display:flex;flex-direction:column;gap:4px}
            .engine-opt{
              width:100%;display:flex;align-items:center;justify-content:space-between;
              padding:10px 12px;border-radius:10px;border:0;background:transparent;
              color:$text;font-size:14px;cursor:pointer;text-align:left
            }
            .engine-opt.active{background:rgba(37,99,235,0.12);color:$accentSoft;font-weight:600}
            .opt-left{display:flex;align-items:center;gap:10px}
            .opt-mark{font-size:11px;font-weight:700;color:$accentSoft}
            .custom-config{border-top:1px solid $border;margin-top:4px;padding-top:12px;border-radius:0 0 10px 10px}
            .eng-dot{
              width:22px;height:22px;border-radius:50%;display:grid;place-items:center;
              font-size:12px;font-weight:700;background:$panelElevated;color:$accentSoft
            }

            .quick-row{
              width:100%;max-width:600px;display:grid;grid-template-columns:repeat(4,1fr);
              gap:10px;margin-bottom:26px
            }
            .qbtn{
              display:flex;flex-direction:column;align-items:center;gap:6px;
              padding:12px 6px;border-radius:14px;background:$panel;border:1px solid $border;
              color:$text;text-decoration:none;font-size:12px;font-weight:500
            }
            .qicon{color:$accentSoft;display:grid;place-items:center}

            .section-title{
              width:100%;max-width:600px;font-size:12px;font-weight:600;color:$muted;
              text-transform:uppercase;letter-spacing:0.06em;margin:0 0 12px
            }
            .shortcuts-grid{
              width:100%;max-width:600px;display:grid;
              grid-template-columns:repeat(4,minmax(0,1fr));gap:14px
            }
            .shortcut{
              display:flex;flex-direction:column;align-items:center;gap:6px;
              color:$text;text-decoration:none;font-size:12px;text-align:center
            }
            .shortcut-icon{
              width:52px;height:52px;border-radius:16px;background:$panel;
              border:1px solid $border;display:grid;place-items:center;
              font-size:19px;font-weight:700;color:$accentSoft;
              box-shadow:0 4px 12px $shadow
            }
            .shortcut-label{
              width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
              color:$text;font-size:12px
            }
            .offline-tag{
              font-size:10px;font-weight:600;padding:1px 6px;border-radius:999px;
              background:rgba(22,163,74,0.14);color:${if (isDark) "#4ADE80" else "#15803D"}
            }
            .empty{
              width:100%;max-width:600px;background:$panel;border:1px dashed $border;
              border-radius:16px;padding:22px;text-align:center
            }
            .empty-title{font-size:14px;font-weight:600;color:$text;margin-bottom:4px}
            .empty-sub{font-size:13px;color:$muted}
            </style>
            </head>
            <body>
            <div class="page">
              <div class="brand">
                <div class="ant-badge">
                  <svg width="38" height="38" viewBox="0 0 64 64" fill="none" aria-hidden="true">
                    <ellipse cx="32" cy="18" rx="6" ry="7" fill="$accent"/>
                    <ellipse cx="32" cy="30" rx="5" ry="7" fill="$accentSoft"/>
                    <ellipse cx="32" cy="46" rx="9" ry="11" fill="$accent"/>
                    <path d="M27 14L20 6M37 14L44 6M27 26L14 21M37 26L50 21M27 32L12 34M37 32L52 34M26 39L14 50M38 39L50 50" stroke="$text" stroke-width="3" stroke-linecap="round"/>
                  </svg>
                </div>
                <h1>Pipra</h1>
                <div class="sub">Fast &amp; Private Mobile Browsing</div>
                <a href="pipra://stats" style="margin-top:10px;display:inline-flex;align-items:center;gap:8px;padding:6px 12px;border-radius:999px;background:$panel;border:1px solid $border;color:$accentSoft;font-size:12px;font-weight:600;text-decoration:none;">
                  <span>🛡 $privacyTotalBlocked blocked · $httpsUpgrades HTTPS</span>
                  ${if (activeExtensionCount > 0) "<span>· $activeExtensionCount ext</span>" else ""}
                </a>
              </div>

              <div class="search-wrap">
                <form class="search-box" onsubmit="event.preventDefault(); var v=this.q.value.trim(); if(v){location.href='pipra://search?q='+encodeURIComponent(v);}">
                  <button type="button" class="engine-trigger" onclick="document.getElementById('engMenu').classList.toggle('open')" aria-label="Choose search engine">
                    $activeBadgeSvg
                    <span class="chev">▾</span>
                  </button>
                  <input type="text" name="q" placeholder="Search $safeEngine or enter URL…" autocomplete="off">
                  <button type="submit" class="search-btn" aria-label="Search">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
                      <circle cx="11" cy="11" r="7"></circle>
                      <line x1="16.65" y1="16.65" x2="21" y2="21"></line>
                    </svg>
                  </button>
                </form>
                <div id="engMenu" class="engine-menu">
                  $engineOptionsHtml
                </div>
              </div>

              <div class="quick-row">
                <a class="qbtn" href="pipra://bookmarks">
                  <span class="qicon">★</span>
                  <span>Bookmarks</span>
                </a>
                <a class="qbtn" href="pipra://history">
                  <span class="qicon">↺</span>
                  <span>History</span>
                </a>
                <a class="qbtn" href="pipra://downloads">
                  <span class="qicon">↓</span>
                  <span>Downloads</span>
                </a>
                <a class="qbtn" href="pipra://extensions">
                  <span class="qicon">🧩</span>
                  <span>Extensions</span>
                </a>
                <a class="qbtn" href="pipra://focus">
                  <span class="qicon">⏱</span>
                  <span>Focus</span>
                </a>
                <a class="qbtn" href="pipra://stats">
                  <span class="qicon">🛡</span>
                  <span>Shields</span>
                </a>
                <a class="qbtn" href="pipra://game">
                  <span class="qicon">🐜</span>
                  <span>Ant Dash</span>
                </a>
                <a class="qbtn" href="pipra://settings">
                  <span class="qicon">⚙</span>
                  <span>Settings</span>
                </a>
              </div>

              <div class="section-title">Bookmarks &amp; Shortcuts</div>
              $bookmarkSection
            </div>
            <script>
              document.addEventListener('click', function(e) {
                var wrap = document.querySelector('.search-wrap');
                var menu = document.getElementById('engMenu');
                if (wrap && menu && !wrap.contains(e.target)) menu.classList.remove('open');
              });
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun engineSvgBadge(engine: String, defaultAccent: String): String {
        val (bg, letter) = when (engine) {
            "Google" -> "#4285F4" to "G"
            "DuckDuckGo" -> "#DE5833" to "D"
            "Bing" -> "#008373" to "B"
            "Brave Search" -> "#FB542B" to "Br"
            "Startpage" -> "#6573FF" to "S"
            "Yahoo" -> "#6001D2" to "Y"
            "Ecosia" -> "#3D9A50" to "E"
            "Qwant" -> "#5C97FF" to "Q"
            else -> defaultAccent to "C"
        }
        return "<span style=\"width:22px;height:22px;border-radius:50%;background:$bg;color:#FFFFFF;display:inline-grid;place-items:center;font-size:11px;font-weight:700;flex-shrink:0;\">$letter</span>"
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
