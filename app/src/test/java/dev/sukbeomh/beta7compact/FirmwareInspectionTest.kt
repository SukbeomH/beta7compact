package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.research.inspectFirmware
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.*

class FirmwareInspectionTest {
    @Test fun emptyAndKnownDigest() {
        val empty = inspectFirmware(byteArrayOf().inputStream())
        assertEquals(0L, empty.size)
        assertEquals("", empty.headerHex)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.sha256)
        val abc = inspectFirmware("abc".byteInputStream())
        assertEquals(3L, abc.size)
        assertEquals("61 62 63", abc.headerHex)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc.sha256)
    }

    @Test fun fragmentedReadsPreserveDigestAndOnlyFirst64Bytes() {
        val data = ByteArray(200000) { (it % 256).toByte() }
        val whole = inspectFirmware(data.inputStream())
        val fragmented = inspectFirmware(object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
        })
        assertEquals(whole, fragmented)
        assertEquals(200000L, whole.size)
        assertEquals((0..63).joinToString(" ") { "%02x".format(it) }, whole.headerHex)
    }

    @Test fun readFailureDoesNotReturnPartialDigest() {
        val input = object : InputStream() {
            var count = 0
            override fun read(): Int = throw IOException("unavailable")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count++ > 0) throw IOException("removed")
                b[off] = 1
                return 1
            }
        }
        assertFailsWith<IOException> { inspectFirmware(input) }
    }

    @Test fun zeroProgressFailsWithoutLooping() {
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertFailsWith<IOException> { inspectFirmware(input) }
    }

    @Test fun cancellationStopsBeforeNextReadAndCallerOwnsClose() {
        var reads = 0
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(100000)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return super.read(b, off, len)
            }
            override fun close() { closed = true; super.close() }
        }
        assertFailsWith<InterruptedException> {
            inspectFirmware(input) { if (reads == 1) throw InterruptedException() }
        }
        assertEquals(1, reads)
        assertFalse(closed)
        input.close()
        assertTrue(closed)
    }
}
