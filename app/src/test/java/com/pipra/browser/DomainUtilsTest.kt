package com.pipra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DomainUtilsTest {

    @Test
    fun `normalizeHost extracts clean lowercase host from urls and mixed-case inputs`() {
        assertEquals("m.youtube.com", DomainUtils.normalizeHost("HTTPS://M.YouTube.COM/watch?v=123#top"))
        assertEquals("example.org", DomainUtils.normalizeHost("  Example.ORG:8443/path/to/page  "))
        assertEquals("sub.domain.co.uk", DomainUtils.normalizeHost("sub.domain.co.uk."))
        assertEquals("93.184.216.34", DomainUtils.normalizeHost("http://93.184.216.34:8080/test"))
    }

    @Test
    fun `normalizeHost rejects malformed inputs, wildcards, userinfo, and unsafe schemes`() {
        assertNull(DomainUtils.normalizeHost(""))
        assertNull(DomainUtils.normalizeHost("   "))
        assertNull(DomainUtils.normalizeHost("*.youtube.com"))
        assertNull(DomainUtils.normalizeHost("not a domain"))
        assertNull(DomainUtils.normalizeHost("singlelabel"))
        assertNull(DomainUtils.normalizeHost("javascript:alert(1)"))
        assertNull(DomainUtils.normalizeHost("file:///etc/passwd"))
        assertNull(DomainUtils.normalizeHost("content://media/external"))
        assertNull(DomainUtils.normalizeHost("data:text/html,hi"))
        assertNull(DomainUtils.normalizeHost("https://user:pass@example.com"))
        assertNull(DomainUtils.normalizeHost("example..com"))
        assertNull(DomainUtils.normalizeHost("-badlabel.com"))
    }

    @Test
    fun `matchesHost prevents partial-domain false positives`() {
        // Exact host only
        assertTrue(DomainUtils.matchesHost("m.youtube.com", "m.youtube.com", includeSubdomains = false))
        assertFalse(DomainUtils.matchesHost("youtube.com", "m.youtube.com", includeSubdomains = false))
        assertFalse(DomainUtils.matchesHost("www.m.youtube.com", "m.youtube.com", includeSubdomains = false))

        // Subdomains enabled
        assertTrue(DomainUtils.matchesHost("example.com", "example.com", includeSubdomains = true))
        assertTrue(DomainUtils.matchesHost("sub.example.com", "example.com", includeSubdomains = true))
        assertTrue(DomainUtils.matchesHost("a.b.example.com", "example.com", includeSubdomains = true))

        // Critical partial-domain false-positive prevention
        assertFalse(DomainUtils.matchesHost("notexample.com", "example.com", includeSubdomains = true))
        assertFalse(DomainUtils.matchesHost("myexample.com", "example.com", includeSubdomains = true))
        assertFalse(DomainUtils.matchesHost("example.com.evil.org", "example.com", includeSubdomains = true))
    }

    @Test
    fun `resolveOmniboxInput blocks unsafe schemes and routes search queries safely`() {
        val search = "https://duckduckgo.com/?q="
        val home = "pipra://home"

        assertEquals(home, DomainUtils.resolveOmniboxInput("   ", search, home))
        assertEquals("pipra://home", DomainUtils.resolveOmniboxInput("pipra://home", search, home))
        assertEquals("https://m.youtube.com/feed", DomainUtils.resolveOmniboxInput("m.youtube.com/feed", search, home))
        assertEquals("http://localhost:8080", DomainUtils.resolveOmniboxInput("http://localhost:8080", search, home))

        val jsResolved = DomainUtils.resolveOmniboxInput("javascript:alert(1)", search, home)
        assertTrue(jsResolved.startsWith(search))

        val fileResolved = DomainUtils.resolveOmniboxInput("file:///sdcard/secret.txt", search, home)
        assertTrue(fileResolved.startsWith(search))

        val dataResolved = DomainUtils.resolveOmniboxInput("data:text/html,<script>", search, home)
        assertTrue(dataResolved.startsWith(search))

        val queryResolved = DomainUtils.resolveOmniboxInput("privacy browser android", search, home)
        assertTrue(queryResolved.startsWith(search))
    }

    @Test
    fun `sanitizeDownloadFileName neutralizes path traversal and illegal chars`() {
        val clean = DomainUtils.sanitizeDownloadFileName("../../etc/evil:file*name?.apk")
        assertFalse(clean.contains(".."))
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains(":"))
        assertFalse(clean.contains("*"))
        assertFalse(clean.contains("?"))
        assertTrue(clean.endsWith(".apk"))
    }
}
