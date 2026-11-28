package com.pipra.browser

import java.util.Locale

data class ReaderArticle(
    val title: String,
    val host: String,
    val url: String,
    val paragraphs: List<String>,
    val wordCount: Int
) {
    val estimatedMinutes: Int
        get() = ((wordCount + 199) / 200).coerceAtLeast(1)
}

/**
 * Pure Kotlin HTML-to-Reader article extractor and distraction-free reader HTML renderer.
 * Strips nav/header/footer/script/style blocks and produces customizable Light, Sepia, or Dark reader views.
 */
object ReaderModeHelper {

    fun extractFromHtml(rawHtml: String, pageUrl: String, fallbackTitle: String = ""): ReaderArticle {
        val host = try {
            java.net.URI(pageUrl).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: DomainUtils.extractHostFromUrl(pageUrl) ?: "Webpage"
        val extractedTitle = Regex("(?is)<title[^>]*>(.*?)</title>")
            .find(rawHtml)?.groupValues?.getOrNull(1)
            ?.let(::stripTagsAndDecode)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.ifBlank { host }

        // Prefer <article> or <main> content if large enough; fall back to <body>
        val articleBody = Regex("(?is)<article[^>]*>(.*?)</article>")
            .find(rawHtml)?.groupValues?.getOrNull(1)
            ?.takeIf { it.length > 250 }
            ?: Regex("(?is)<main[^>]*>(.*?)</main>")
                .find(rawHtml)?.groupValues?.getOrNull(1)
                ?.takeIf { it.length > 250 }
            ?: Regex("(?is)<body[^>]*>(.*?)</body>")
                .find(rawHtml)?.groupValues?.getOrNull(1)
            ?: rawHtml

        val cleaned = articleBody
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|aside|form|iframe)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")

        val blocks = mutableListOf<String>()
        val blockMatcher = Regex("(?is)<(h1|h2|h3|p|blockquote|li)[^>]*>(.*?)</\\1>")
        for (match in blockMatcher.findAll(cleaned)) {
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val text = stripTagsAndDecode(match.groupValues[2])
            if (text.length >= 18 || (tag.startsWith("h") && text.length >= 4)) {
                if (tag.startsWith("h")) blocks.add("## $text")
                else blocks.add(text)
            }
        }

        if (blocks.isEmpty()) {
            val plain = stripTagsAndDecode(cleaned)
                .split(Regex("\\n+"))
                .map { it.trim() }
                .filter { it.length >= 20 }
            blocks.addAll(plain)
        }

        val finalParagraphs = if (blocks.isEmpty()) {
            listOf("No structured article text was detected on this page. Switch back to standard view for interactive content.")
        } else {
            blocks.take(250)
        }
        val words = finalParagraphs.sumOf { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        return ReaderArticle(
            title = extractedTitle,
            host = host,
            url = pageUrl,
            paragraphs = finalParagraphs,
            wordCount = words
        )
    }

    fun renderReaderHtml(
        article: ReaderArticle,
        theme: String,
        fontSizeSp: Int
    ): String {
        val (bg, text, muted, card, accent) = when (theme.lowercase(Locale.ROOT)) {
            "dark" -> listOf("#0F1115", "#E5E7EB", "#9CA3AF", "#181B22", "#60A5FA")
            "light" -> listOf("#FFFFFF", "#111827", "#4B5563", "#F3F4F6", "#2563EB")
            else -> listOf("#F5EEDC", "#3B2F20", "#6B5B45", "#ECE2C8", "#92400E") // sepia
        }
        val safeTitle = Codec.htmlEncode(article.title)
        val safeHost = Codec.htmlEncode(article.host)
        val sizePx = fontSizeSp.coerceIn(14, 28)

        val bodyHtml = article.paragraphs.joinToString("\n") { p ->
            if (p.startsWith("## ")) {
                "<h2>${Codec.htmlEncode(p.removePrefix("## "))}</h2>"
            } else {
                "<p>${Codec.htmlEncode(p)}</p>"
            }
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>$safeTitle</title>
              <style>
                body {
                  margin: 0; padding: 24px 18px 48px;
                  background: $bg; color: $text;
                  font-family: Georgia, "Times New Roman", serif;
                  font-size: ${sizePx}px;
                  line-height: 1.72;
                }
                article { max-width: 680px; margin: 0 auto; }
                .meta {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  font-size: 13px; color: $muted;
                  background: $card; border-radius: 10px;
                  padding: 8px 12px; margin-bottom: 18px;
                  display: inline-block;
                }
                h1 {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  font-size: ${(sizePx + 8)}px; line-height: 1.25; margin: 0 0 12px;
                }
                h2 {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  font-size: ${(sizePx + 3)}px; margin: 24px 0 8px; color: $accent;
                }
                p { margin: 0 0 16px; }
              </style>
            </head>
            <body>
              <article>
                <div class="meta">$safeHost · ${article.estimatedMinutes} min read (${article.wordCount} words)</div>
                <h1>$safeTitle</h1>
                $bodyHtml
              </article>
            </body>
            </html>
        """.trimIndent()
    }

    private fun stripTagsAndDecode(raw: String): String {
        return raw
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
