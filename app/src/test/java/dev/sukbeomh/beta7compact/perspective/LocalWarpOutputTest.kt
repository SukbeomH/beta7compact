package dev.sukbeomh.beta7compact.perspective

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.*

class LocalWarpOutputTest {
    @Test fun successfulWritePublishesExactlyOnceAndKeepsOutput() = runBlocking {
        val events = mutableListOf<String>()
        val result = publishLocalWarpOutput(
            create = { events += "create"; "output" },
            write = { events += "write:$it" },
            publish = { events += "publish:$it" },
            delete = { events += "delete:$it" },
        )
        assertEquals("output", result)
        assertEquals(listOf("create", "write:output", "publish:output"), events)
    }

    @Test fun failedWriteAndPublicationBothRemoveCreatedOutput() = runBlocking {
        for (failWrite in listOf(true, false)) {
            val events = mutableListOf<String>()
            val failure = IllegalStateException("synthetic failure")
            val actual = assertFailsWith<IllegalStateException> {
                publishLocalWarpOutput(
                    create = { "output" },
                    write = { if (failWrite) throw failure else events += "write" },
                    publish = { throw failure },
                    delete = { events += "delete" },
                )
            }
            assertSame(failure, actual)
            assertEquals(if (failWrite) listOf("delete") else listOf("write", "delete"), events)
        }
    }

    @Test fun cancellationAfterPublishAlsoRemovesOutput() = runBlocking {
        val events = mutableListOf<String>()
        var checks = 0
        assertFailsWith<CancellationException> {
            publishLocalWarpOutput(
                create = { "output" },
                write = { events += "write" },
                publish = { events += "publish" },
                delete = { events += "delete" },
                checkpoint = { if (++checks == 2) throw CancellationException("synthetic cancellation") },
            )
        }
        assertEquals(listOf("write", "publish", "delete"), events)
    }

    @Test fun canceledCoroutineStillRunsCleanup() = runBlocking {
        var cleaned = false
        val saveJob = Job()
        var operationJob: Job? = null
        assertFailsWith<CancellationException> {
            withContext(saveJob) {
                publishLocalWarpOutput(
                    create = { "output" },
                    write = {
                        operationJob = currentCoroutineContext()[Job]
                        currentCoroutineContext().cancel()
                        currentCoroutineContext().ensureActive()
                    },
                    publish = { fail("Canceled write must not publish") },
                    delete = { cleaned = true },
                )
            }
        }
        assertTrue(cleaned)
        assertFalse(checkNotNull(operationJob).isActive)
        // withContext cancels its child operation, not this independently supplied parent Job.
        saveJob.cancel()
    }

    @Test fun cleanupFailureDoesNotHidePrimaryError() = runBlocking {
        val primary = IllegalStateException("write failed")
        val cleanup = IllegalArgumentException("delete failed")
        val actual = assertFailsWith<IllegalStateException> {
            publishLocalWarpOutput(
                create = { "output" }, write = { throw primary }, publish = {}, delete = { throw cleanup },
            )
        }
        assertSame(primary, actual)
        assertTrue(actual.suppressed.any { it === cleanup })
    }

    @Test fun jpegSamplingUsesCeilingBoundsAndHandlesExtremeDimensions() {
        for (maxSide in listOf(1200, 2048)) {
            for ((width, height) in listOf(6000 to 4000, 4097 to 4097, Int.MAX_VALUE to 1, 1 to 1, 2048 to 2048)) {
                val sample = localWarpSampleSize(width, height, maxSide)
                assertTrue((maxOf(width, height).toLong() + sample - 1) / sample <= maxSide)
                assertTrue(sample > 0 && sample and (sample - 1) == 0)
            }
        }
        assertFailsWith<IllegalArgumentException> { localWarpSampleSize(0, 100, 1200) }
    }
}
