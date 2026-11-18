package dev.sukbeomh.beta7compact

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.Wire
import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.*
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

/** Synthetic USB bytes; no real device, card, photo or capture success is represented. */
class SonyCaptureIntegrationTest {
    private fun codes(vararg n: Int) = buffer(4 + n.size * 2).putInt(n.size).apply { n.forEach { putShort(it.toShort()) } }.array()
    private fun text(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun device() = buffer(8).putShort(100).putInt(17).putShort(100).array() + byteArrayOf(0, 0, 0) +
        codes(0x9201, 0x9202, 0x9209, 0x9207) + codes() + codes() + codes() + codes() +
        text("Sony Corporation") + text("ILCE-7C") + text("synthetic") + text("")
    private fun property(code: Int, type: Int, value: Long, button: Boolean = false): ByteArray {
        val width = 1 shl ((type - 1) / 2)
        val raw = ByteArray(width) { (value ushr (it * 8)).toByte() }
        return buffer(6).putShort(code.toShort()).putShort(type.toShort()).put(if (button) 0x81.toByte() else 1).put(1).array() + raw + raw + byteArrayOf(0)
    }

    private inner class Peer : SonyRemoteWire {
        var pending = byteArrayOf()
        var activeCommand: PtpContainer? = null
        val controls = mutableListOf<Pair<Int, Int>>()
        var half = false
        var full = false
        var focusPolls = 0
        var objectPending = false
        var closes = 0
        fun snapshot(): ByteArray {
            val props = listOf(property(0xd25a, 2, 1), property(0xd222, 4, 0x10),
                property(0x500e, 4, 1), property(0x5013, 4, 1), property(0x500a, 4, 2),
                property(0xd213, 2, if (half && ++focusPolls > 1) 2 else 1), property(0xd20d, 6, 0x0001007d),
                property(0xd215, 4, 0), property(0xd2c1, 4, if (half) 2 else 1, true),
                property(0xd2c2, 4, if (full) 2 else 1, true))
            return buffer(8).putInt(props.size).putInt(0).array() + props.fold(byteArrayOf()) { a, b -> a + b }
        }
        override suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int {
            val c = PtpContainer.decode(bytes)
            if (c.type == 2) {
                val command = checkNotNull(activeCommand)
                assertEquals(command.transaction, c.transaction)
                assertEquals(0x9207, c.code)
                val prop = le(command.payload).int
                val value = le(c.payload).short.toInt()
                controls += prop to value
                if (prop == 0xd2c1) { half = value == 2; focusPolls = 0 }
                if (prop == 0xd2c2) { full = value == 2; if (full) objectPending = true }
                pending = PtpContainer(3, 0x2001, c.transaction, byteArrayOf()).encode()
            } else {
                assertTrue(pending.isEmpty())
                activeCommand = c
                if (c.code == 0x9207) return bytes.size
                val payload = when(c.code) {
                    0x1001 -> device()
                    0x9201 -> ByteArray(8)
                    0x9202 -> byteArrayOf(0xc8.toByte(), 0) + codes(0x9207) + codes(0xd2c1, 0xd2c2)
                    0x9209 -> snapshot()
                    else -> null
                }
                pending = (payload?.let { PtpContainer(2, c.code, c.transaction, it).encode() } ?: byteArrayOf()) +
                    PtpContainer(3, 0x2001, c.transaction, byteArrayOf()).encode()
            }
            return bytes.size
        }
        override suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int {
            val n = minOf(7, pending.size)
            pending.copyInto(bytes, 0, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }
        override suspend fun readEvent(timeoutMillis: Int): ByteArray? {
            if (!objectPending) return null
            objectPending = false
            return PtpContainer(4, 0xc201, -1, buffer(4).putInt(77).array()).encode()
        }
        override fun close() { closes++ }
    }

    @Test fun completeCaptureUsesTypedWireAndNeverClaimsSavedPhoto() = runBlocking {
        var now = 0L
        val peer = Peer()
        val transport = SonySessionWireTransport(peer, allowCaptureControls = true)
        val session = SonyCaptureSession(transport, clockMillis = { now }, pause = { now += it })
        session.connect()
        val result = session.captureSingle()
        assertEquals("SUBMITTED_UNCONFIRMED", result.outcome.name)
        assertEquals(listOf(0xd2c1 to 2, 0xd2c2 to 2, 0xd2c2 to 1, 0xd2c1 to 1), peer.controls)
        assertFalse(peer.half); assertFalse(peer.full)
        session.close()
        assertEquals(1, peer.closes)
    }

    @Test fun captureScopeExcludesLegacyImportAndReturnsOwnershipAfterCancellation() = runBlocking {
        Wire.reset(); Wire.deviceInfo = device()
        val context = Context(UsbManager())
        val peer = Peer()
        val entered = CompletableDeferred<Unit>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.useCapture({ SonySessionWireTransport(peer, allowCaptureControls = true) }) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await()
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.use(context, false) { assertEquals(1, peer.closes); it.list().size }
        }
        val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { UsbAccess.inspect(context) }
        assertEquals(0, Wire.rawOpens); assertEquals(0, Wire.nativeOpens)
        task.cancelAndJoin()
        assertEquals(1, importing.await())
        assertEquals("ILCE-7C", diagnostic.await().model)
        assertTrue(peer.controls.isEmpty())
        assertEquals(0, Wire.setCalls)
    }

    @Test fun captureFactoryFailureClosesTransportAndReturnsUsbOwnership() = runBlocking {
        Wire.reset(); Wire.deviceInfo = device()
        val peer = Peer()
        assertFailsWith<IllegalStateException> {
            UsbAccess.useCapture(openTransport = { SonySessionWireTransport(peer, allowCaptureControls = true) },
                sessionFactory = { error("synthetic factory error") }) { error("unreachable") }
        }
        assertEquals(1, peer.closes)
        assertTrue(peer.controls.isEmpty())
        assertEquals("ILCE-7C", UsbAccess.inspect(Context(UsbManager())).model)
    }
}
