package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.cache.ThumbnailCache
import org.junit.Test
import kotlin.test.*

class ThumbnailCacheTest {
    @Test fun repeatedReadsReuseValueAndEvictLeastRecentlyUsed() {
        val cache = ThumbnailCache<String>(6) { it.length }
        val g = cache.token()
        cache.put("a", "aaa", g); cache.put("b", "bbb", g)
        assertEquals("aaa", cache.get("a"))
        cache.put("c", "ccc", g)
        assertNull(cache.get("b")); assertEquals("aaa", cache.get("a")); assertEquals("ccc", cache.get("c"))
    }
    @Test fun disconnectRejectsInFlightOldResults() {
        val cache = ThumbnailCache<String>(6) { it.length }
        val old = cache.token()
        cache.put("a", "aaa", old); cache.clear()
        cache.put("a", "old", old)
        assertNull(cache.get("a"))
        cache.put("a", "new", cache.token())
        cache.retain(emptySet(), old)
        assertEquals("new", cache.get("a"))
    }
    @Test fun refreshedListDropsDeletedOrChangedObjectsAndOversizeIsNotCached() {
        val cache = ThumbnailCache<String>(6) { it.length }
        val g = cache.token()
        cache.put("old", "aaa", g); cache.put("keep", "bbb", g)
        cache.retain(setOf("keep"), g)
        assertNull(cache.get("old")); assertEquals("bbb", cache.get("keep"))
        cache.put("large", "1234567", g)
        assertNull(cache.get("large")); assertEquals("bbb", cache.get("keep"))
    }
}
