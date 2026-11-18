package dev.sukbeomh.beta7compact.perspective

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import kotlin.test.*

class LocalWarpInputTest {
    @Test fun copiesExactLimitAndPreservesEncodedBytes() {
        val source = ByteArray(128 * 1024 + 17) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(source.size.toLong(), copyLocalWarpInput(ByteArrayInputStream(source), output, source.size.toLong()))
        assertContentEquals(source, output.toByteArray())
    }

    @Test fun oversizedStreamNeverWritesBeyondItsLimit() {
        val output = ByteArrayOutputStream()
        assertFailsWith<IOException> {
            copyLocalWarpInput(ByteArrayInputStream(ByteArray(128 * 1024)), output, 64 * 1024L + 1)
        }
        assertTrue(output.size() <= 64 * 1024 + 1)
    }

    @Test fun cancellationStopsBeforeWritingTheNextChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertFailsWith<CancellationException> {
            copyLocalWarpInput(ByteArrayInputStream(ByteArray(128 * 1024)), output, 128 * 1024L) {
                if (++checks == 3) throw CancellationException("synthetic cancellation")
            }
        }
        assertEquals(64 * 1024, output.size())
    }

    @Test fun emptyInputReturnsZeroAndInvalidBudgetIsRejected() {
        assertEquals(0L, copyLocalWarpInput(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 1))
        assertFailsWith<IllegalArgumentException> {
            copyLocalWarpInput(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0)
        }
    }
}
