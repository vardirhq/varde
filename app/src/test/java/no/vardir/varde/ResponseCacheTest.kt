package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ResponseCacheTest {
    private val dir = Files.createTempDirectory("cache").toFile()

    @Test fun savesAndReturnsEntries() {
        val cache = ResponseCache(dir, 10_000)
        cache.put("me:repos/o/r", "{}", etag = "W/\"1\"", savedAt = 5)
        assertEquals(CacheEntry("{}", 5, "W/\"1\""), cache.get("me:repos/o/r"))
        assertNull(cache.get("other:repos/o/r"))
    }

    @Test fun fileNamesDontRevealRequests() {
        ResponseCache(dir, 10_000).put("me:repos/secret-org/secret-repo", "{}")
        assertTrue(dir.listFiles()!!.none { it.name.contains("secret") })
    }

    @Test fun evictsLeastRecentlyUsedWhenFull() {
        val cache = ResponseCache(dir, 2_000)
        cache.put("a", "x".repeat(400)); dir.listFiles()!!.forEach { it.setLastModified(1_000) }
        cache.put("b", "x".repeat(400))
        cache.put("c", "x".repeat(400))
        cache.put("d", "x".repeat(400))
        cache.put("e", "x".repeat(400))
        assertNull(cache.get("a"))
        assertNotNull(cache.get("e"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2_000)
    }

    @Test fun hugeResponsesAreNotSaved() {
        val cache = ResponseCache(dir, 1_000)
        cache.put("big", "x".repeat(600))
        assertNull(cache.get("big"))
    }

    @Test fun clearRemovesEverything() {
        val cache = ResponseCache(dir, 10_000)
        cache.put("a", "1")
        cache.clear()
        assertNull(cache.get("a"))
        assertFalse(dir.exists())
    }
}
