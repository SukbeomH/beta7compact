package dev.sukbeomh.beta7compact

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.Wire
import dev.sukbeomh.beta7compact.remote.SonySessionOperation
import dev.sukbeomh.beta7compact.remote.SonySessionTransport
import dev.sukbeomh.beta7compact.remote.SonyRemoteWire
import dev.sukbeomh.beta7compact.remote.SonySessionWireTransport
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.UsbAccess
import dev.sukbeomh.beta7compact.usb.buffer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic remote peer and existing native/raw fakes; never opens a real USB device. */
class SonyRemoteOwnershipTest {
    private fun codes(vararg values: Int) = buffer(4 + values.size * 2).putInt(values.size)
        .apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun string(value: String) = byteArrayOf((value.length + 1).toByte()) +
        (value + "\u0000").toByteArray(Charsets.UTF_16LE)
    private fun info() = buffer(8).putShort(100).putInt(17).putShort(100).array() +
        byteArrayOf(0, 0, 0) + codes(0x9201, 0x9202, 0x9209) + codes() + codes() + codes() + codes() +
        string("Sony Corporation") + string("ILCE-7C") + string("synthetic") + string("")

    private inner class Peer(val closeFailure: Boolean = false) : SonySessionTransport {
        var closes = 0
        val requests = mutableListOf<SonySessionOperation>()
        override suspend fun exchange(operation: SonySessionOperation): ByteArray {
            requests += operation
            return when (operation) {
                SonySessionOperation.DEVICE_INFO -> info()
                SonySessionOperation.EXTENDED_INFO -> byteArrayOf(0xc8.toByte(), 0) + codes() + codes()
                SonySessionOperation.PROPERTY_SNAPSHOT -> ByteArray(8)
                SonySessionOperation.CLOSE_SESSION -> {
                    if (closeFailure) throw IOException("synthetic close failure")
                    byteArrayOf()
                }
                else -> byteArrayOf()
            }
        }
        override suspend fun nextEvent(timeoutMillis: Int): ByteArray? = null
        override fun close() { closes++ }
    }

    private fun setup(): Context {
        Wire.reset()
        Wire.deviceInfo = info()
        return Context(UsbManager())
    }

    @Test fun remoteScopeBlocksImportAndDiagnosticsUntilTransportClosed() = runBlocking {
        val context = setup()
        val peer = Peer()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.useRemote({ peer }) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.use(context, false) { assertEquals(1, peer.closes); it.list().size }
        }
        val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { UsbAccess.inspect(context) }
        assertEquals(0, Wire.nativeOpens)
        assertEquals(0, Wire.rawOpens)
        assertFalse(importing.isCompleted)
        assertFalse(diagnostic.isCompleted)
        release.complete(Unit)
        remote.join()
        assertEquals(1, importing.await())
        assertEquals("ILCE-7C", diagnostic.await().model)
        assertEquals(1, peer.closes)
        assertEquals(0, Wire.setCalls)
        assertTrue(Wire.operations.none { it in 0x9200..0x92ff })
    }

    @Test fun importOwnershipDefersRemoteFactoryAndCancelledWaitNeverOpensIt() = runBlocking {
        val context = setup()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val importing = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.use(context, false) { held.complete(Unit); release.await() }
        }
        held.await()
        var factories = 0
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.useRemote({ factories++; Peer() }) { error("Must remain queued") }
        }
        assertEquals(0, factories)
        cancelled.cancelAndJoin()
        val peer = Peer()
        val remote = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.useRemote({ factories++; peer }) { assertFalse(Wire.nativeOwned) }
        }
        assertEquals(0, factories)
        release.complete(Unit)
        importing.join(); remote.join()
        assertEquals(1, factories)
        assertEquals(1, peer.closes)
    }

    @Test fun cancellationClosesRemoteAndRestoresDiagnosticOwnership() = runBlocking {
        val context = setup()
        val peer = Peer()
        val entered = CompletableDeferred<Unit>()
        val remote = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.useRemote({ peer }) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        remote.cancelAndJoin()
        assertEquals(1, peer.closes)
        assertEquals(1, peer.requests.count { it == SonySessionOperation.CLOSE_SESSION })
        assertEquals("ILCE-7C", UsbAccess.inspect(context).model)
    }

    @Test fun blockFailureSurvivesCloseFailureAndLockIsReleased() = runBlocking {
        val context = setup()
        val peer = Peer(closeFailure = true)
        val primary = IllegalStateException("synthetic block failure")
        val actual = assertFailsWith<IllegalStateException> {
            UsbAccess.useRemote({ peer }) { throw primary }
        }
        assertSame(primary, actual)
        assertTrue(actual.suppressed.isNotEmpty())
        assertEquals(1, peer.closes)
        assertEquals("ILCE-7C", UsbAccess.inspect(context).model)
    }

    @Test fun factoryFailureDoesNotRetainUsbOwnership() = runBlocking {
        val context = setup()
        assertFailsWith<IOException> {
            UsbAccess.useRemote({ throw IOException("synthetic factory failure") }) { error("unreachable") }
        }
        assertEquals("ILCE-7C", UsbAccess.inspect(context).model)
    }

    @Test fun completeSessionRunsThroughFragmentedByteTransportAndRefreshesAfterEvent() = runBlocking {
        setup()
        val requests = mutableListOf<PtpContainer>()
        var pending = byteArrayOf()
        var closes = 0
        val wire = object : SonyRemoteWire {
            override suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int {
                assertTrue(pending.isEmpty())
                val command = PtpContainer.decode(bytes)
                requests += command
                val data = when (command.code) {
                    0x1001 -> info()
                    0x9201 -> ByteArray(8)
                    0x9202 -> byteArrayOf(0x2c, 1) + codes() + codes()
                    0x9209 -> ByteArray(8)
                    else -> null
                }
                pending = (data?.let { PtpContainer(2, command.code, command.transaction, it).encode() }
                    ?: byteArrayOf()) + PtpContainer(3, 0x2001, command.transaction, byteArrayOf()).encode()
                return bytes.size
            }
            override suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int {
                val n = minOf(7, pending.size)
                pending.copyInto(bytes, 0, 0, n)
                pending = pending.copyOfRange(n, pending.size)
                return n
            }
            override suspend fun readEvent(timeoutMillis: Int) =
                byteArrayOf(16, 0, 0, 0, 4, 0, 3, 0xc2.toByte(), -1, -1, -1, -1, 0x1e, 0xd2.toByte(), 0, 0)
            override fun close() { closes++ }
        }
        UsbAccess.useRemote({ SonySessionWireTransport(wire) }) { session ->
            assertFalse(checkNotNull(session.snapshot).propertiesStale)
            assertEquals(0xc203, session.pollEvent(100)?.code)
            assertTrue(checkNotNull(session.snapshot).propertiesStale)
            assertFalse(session.refreshProperties().propertiesStale)
        }
        assertEquals(listOf(0x1001, 0x1002, 0x9201, 0x9201, 0x9202, 0x9201, 0x9209, 0x9209, 0x1003), requests.map { it.code })
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 5, 6, 7), requests.map { it.transaction })
        assertEquals(1, closes)
        assertTrue(pending.isEmpty())
    }
}
