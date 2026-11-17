package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.cache.DiskThumbnailCache
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class DiskThumbnailCacheTest {
    @Test fun reconnectReadsDiskAndCapacityEvictsOldest() {
        val dir = Files.createTempDirectory("thumb-test").toFile()
        try {
            val cache = DiskThumbnailCache(dir, 6, 4)
            cache.put("camera-a/photo", byteArrayOf(1,2,3))
            dir.listFiles()!!.forEach { it.setLastModified(1) }
            cache.put("camera-a/other", byteArrayOf(4,5,6))
            assertContentEquals(byteArrayOf(1,2,3), DiskThumbnailCache(dir,6,4).get("camera-a/photo"))
            dir.listFiles()!!.first { it.name.startsWith(DiskThumbnailCache.hash("camera-a/other")) }.setLastModified(1)
            cache.put("camera-a/new", byteArrayOf(7,8,9))
            assertNull(cache.get("camera-a/other"))
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 6)
            assertNull(cache.get("camera-b/photo"))
            cache.put("oversize", ByteArray(5))
            assertNull(cache.get("oversize"))
        } finally { dir.deleteRecursively() }
    }
}
