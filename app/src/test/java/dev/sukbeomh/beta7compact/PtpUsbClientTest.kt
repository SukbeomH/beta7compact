package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.usb.*

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import org.junit.Test
import kotlin.test.*

private fun bytes(hex: String) = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class PtpUsbClientTest {
    private fun PtpUsbClient.inspect(): ObjectInfo {
        val info = Ptp.deviceInfo(read(0x1001))
        logs.add("model=${info.model} version=${info.version}")
        openSession()
        return Ptp.latest(Ptp.ids(read(0x1004)).flatMap { storage ->
            Ptp.ids(read(0x1007, storage, 0, 0)).map { Ptp.objectInfo(it, read(0x1008, it)) }
        })!!
    }
    private data class Step(val command: String, val replies: List<ByteArray>)
    private class FakeUsb(steps: List<Step>, private val fragment: Int = 16384) : UsbBulkTransport {
        private val script = ArrayDeque(steps)
        private val incoming = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        var writeResult: Int? = null
        var readFailure = -1
        override fun write(bytes: ByteArray, timeoutMs: Int): Int {
            assertEquals(8000, timeoutMs)
            sent.add(bytes.copyOf())
            writeResult?.let { return it }
            assertTrue(incoming.isEmpty(), "Client sent command before consuming response")
            val step = script.removeFirst()
            assertContentEquals(bytes(step.command), bytes)
            for (reply in step.replies) {
                if (reply.isEmpty()) incoming.add(reply)
                else reply.asList().chunked(fragment).forEach { incoming.add(it.toByteArray()) }
            }
            return bytes.size
        }
        override fun read(bytes: ByteArray, timeoutMs: Int): Int {
            assertEquals(8000, timeoutMs)
            assertEquals(16384, bytes.size, "IN requests must fit full USB packets, including data after header")
            if (incoming.isEmpty()) return readFailure
            val chunk = incoming.removeFirst()
            val n = minOf(chunk.size, bytes.size)
            chunk.copyInto(bytes, 0, 0, n)
            if (chunk.size > n) incoming.addFirst(chunk.copyOfRange(n, chunk.size))
            return n
        }
        fun finish() { assertTrue(script.isEmpty()); assertTrue(incoming.isEmpty()) }
    }

    private val logs = mutableListOf<String>()
    private fun response(tx: Int, code: Int = Ptp.OK) = buffer(12).putInt(12).putShort(3).putShort(code.toShort()).putInt(tx).array()
    private fun data(code: Int, tx: Int, payload: ByteArray): ByteArray =
        buffer(12 + payload.size).putInt(12 + payload.size).putShort(2).putShort(code.toShort()).putInt(tx).put(payload).array()
    private fun string(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + "\u0000").toByteArray(Charsets.UTF_16LE)
    private fun deviceInfo() = bytes("640006000000640000000006000000011002100310041008100910") +
        bytes("00000000000000000000000000000000") + string("Sony") + string("ILCE-7C") + string("2.03") + byteArrayOf(0)
    private fun objectInfo(size: Int, name: String = "DSC0042.JPG", date: String = "20261114T141538", format: Int = 0x3801) =
        buffer(52).putInt(0x10001).putShort(format.toShort()).putShort(0).putInt(size).apply { position(52) }.array() +
            string(name) + string(date) + byteArrayOf(0, 0)
    private fun readStep(command: String, code: Int, tx: Int, payload: ByteArray) =
        Step(command, listOf(data(code, tx, payload) + response(tx))) // Data and response may share one read.
    private fun open() = Step("10000000010002100000000001000000", listOf(bytes("0c0000000300012000000000")))
    private fun close(tx: Int) = Step("0c00000001000310%02x000000".format(tx), listOf(response(tx)))
    private fun listing(size: Int = 4) = listOf(
        readStep("0c0000000100011000000000", 0x1001, 0, deviceInfo()), open(),
        readStep("0c0000000100041001000000", 0x1004, 1, bytes("0100000001000100")),
        readStep("180000000100071002000000010001000000000000000000", 0x1007, 2, bytes("010000002a000000")),
        readStep("1000000001000810030000002a000000", 0x1008, 3, objectInfo(size))
    )

    @Test fun fixedCommandsDeviceInfoSessionListingJpegAndCloseRoundTrip() {
        val usb = FakeUsb(listing() + listOf(
            readStep("1000000001000910040000002a000000", 0x1009, 4, bytes("ffd8ffd9")), close(5)
        ), fragment = 1)
        PtpUsbClient(usb, logs::add).use { client ->
            val obj = client.inspect()
            assertEquals("DSC0042.JPG", obj.name)
            assertEquals(42, obj.handle)
            val output = ByteArrayOutputStream()
            assertEquals(4L, client.getObject(obj.handle, output, obj.size))
            assertContentEquals(bytes("ffd8ffd9"), output.toByteArray())
        }
        usb.finish()
        assertTrue(logs.any { "model=ILCE-7C version=2.03" in it })
        for (code in listOf(0x1001, 0x1002, 0x1004, 0x1007, 0x1008, 0x1009, 0x1003)) {
            assertTrue(logs.any { "OP ${hex(code)} response=0x2001" in it && "ms=" in it })
        }
    }

    @Test fun inspectSelectsLatestAcrossStoragesSkipsRawAndDoesNotDownload() {
        val usb = FakeUsb(listOf(
            readStep("0c0000000100011000000000", 0x1001, 0, deviceInfo()), open(),
            readStep("0c0000000100041001000000", 0x1004, 1, bytes("020000000100010002000100")),
            readStep("180000000100071002000000010001000000000000000000", 0x1007, 2, bytes("020000005a0000005b000000")),
            readStep("1000000001000810030000005a000000", 0x1008, 3, objectInfo(4, "OLD.JPG", "20261113T100000")),
            readStep("1000000001000810040000005b000000", 0x1008, 4, objectInfo(4, "RAW.ARW", "20261115T100000", 0xB101)),
            readStep("180000000100071005000000020001000000000000000000", 0x1007, 5, bytes("010000002a000000")),
            readStep("1000000001000810060000002a000000", 0x1008, 6, objectInfo(4)), close(7)
        ))
        PtpUsbClient(usb, logs::add).use { assertEquals(42, it.inspect().handle) }
        usb.finish()
        assertFalse(usb.sent.any { PtpContainer.decode(it).code == 0x1009 })
    }

    @Test fun streamsLargeJpegAndAcceptsZlpBetweenDataAndResponse() {
        val jpeg = ByteArray(65524) { (it % 251).toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte() }
        val usb = FakeUsb(listing(jpeg.size) + listOf(
            Step("1000000001000910040000002a000000", listOf(data(0x1009, 4, jpeg), byteArrayOf(), response(4))), close(5)
        ))
        PtpUsbClient(usb, logs::add).use { c ->
            val obj = c.inspect()
            val output = ByteArrayOutputStream()
            assertEquals(jpeg.size.toLong(), c.getObject(obj.handle, output, obj.size))
            assertContentEquals(jpeg, output.toByteArray())
        }
        usb.finish()
        assertTrue(logs.any { "returned=0 (ZLP)" in it })
    }

    @Test fun alreadyActiveConfigurationNeverCallsSetOrRequery() {
        ensureUsbConfiguration(2, 2, { fail("must not reset active configuration") }, { fail("must not requery") }, logs::add)
        assertTrue(logs.single().contains("setConfiguration SKIP"))
    }

    @Test fun differentConfigurationSetsOnceThenVerifies() {
        val calls = mutableListOf<String>()
        ensureUsbConfiguration(1, 2, { calls.add("set"); true }, { calls.add("read"); 2 }, logs::add)
        assertEquals(listOf("set", "read"), calls)
        assertTrue(logs.any { "result=true" in it && "ms=" in it })
    }

    @Test fun configurationFailureAndWrongReadbackStop() {
        assertFails { ensureUsbConfiguration(1, 2, { false }, { fail("set failed") }, logs::add) }
        assertFails { ensureUsbConfiguration(1, 2, { true }, { 1 }, logs::add) }
        assertFails { ensureUsbConfiguration(-1, 2, { fail("unknown active") }, { 2 }, logs::add) }
    }

    @Test fun cameraErrorKeepsResponseCodeAndStillClosesOpenSession() {
        val usb = FakeUsb(listOf(open(), Step("0c0000000100041001000000", listOf(response(1, 0x2019))), close(2)))
        PtpUsbClient(usb, logs::add).use { c ->
            c.openSession()
            assertEquals(0x2019, assertFailsWith<PtpResponseException> { c.read(0x1004) }.code)
        }
        usb.finish()
        assertTrue(logs.any { "response=0x2019" in it })
    }

    @Test fun rejectsWrongTransactionsCodesTypesAndLengths() {
        for (reply in listOf(
            data(0x1001, 1, byteArrayOf()), data(0x1004, 0, byteArrayOf()),
            bytes("0c0000000400011000000000"), bytes("0b0000000200011000000000"),
            bytes("ffffffff0200011000000000"), bytes("0d000000030001200000000000"),
            data(0x1001, 0, byteArrayOf()) + response(1)
        )) {
            val usb = FakeUsb(listOf(Step("0c0000000100011000000000", listOf(reply))))
            PtpUsbClient(usb, logs::add).use { c ->
                assertFails { c.read(0x1001) }
                assertFails { c.read(0x1001) }
            }
            assertEquals(1, usb.sent.size, "Broken framing must not retry commands")
        }
    }

    @Test fun rejectsMissingAndDuplicateDataPhases() {
        for (reply in listOf(response(0), data(0x1001, 0, byteArrayOf()) + data(0x1001, 0, byteArrayOf()) + response(0))) {
            val usb = FakeUsb(listOf(Step("0c0000000100011000000000", listOf(reply))))
            PtpUsbClient(usb, logs::add).use { assertFails { it.read(0x1001) } }
        }
    }

    @Test fun transferFailuresRetainReturnValuesAndReceivedHex() {
        for (write in listOf(-1, 0, 5)) {
            val usb = FakeUsb(emptyList()).apply { writeResult = write }
            PtpUsbClient(usb, logs::add).use { assertFailsWith<IOException> { it.read(0x1001) } }
            assertTrue(logs.last().contains("bulkTransferOUT=$write"))
        }
        val usb = FakeUsb(listOf(Step("0c0000000100011000000000", listOf(bytes("0c00000002")))))
        PtpUsbClient(usb, logs::add).use { assertFailsWith<IOException> { it.read(0x1001) } }
        assertTrue(logs.last().contains("bulkTransferIN=-1 hex=0C 00 00 00 02"))
    }

    @Test fun truncatedPayloadDoesNotSucceedOrSendCloseOnBrokenTransport() {
        val usb = FakeUsb(listOf(open(), Step("0c0000000100041001000000", listOf(bytes("1400000002000410010000000100")))))
        PtpUsbClient(usb, logs::add).use { c ->
            c.openSession()
            assertFailsWith<IOException> { c.read(0x1004) }
        }
        usb.finish()
        assertEquals(2, usb.sent.size)
        assertTrue(logs.any { "CloseSession SKIP" in it && "unconfirmed" in it })
    }

    @Test fun failureHexKeepsFirst64BytesAcrossFragmentedReads() {
        val reply = bytes("640000000200011000000000") + ByteArray(70) { it.toByte() }
        val usb = FakeUsb(listOf(Step("0c0000000100011000000000", listOf(reply))), fragment = 3)
        PtpUsbClient(usb, logs::add).use { assertFailsWith<IOException> { it.read(0x1001) } }
        assertTrue(logs.last().contains("bulkTransferIN=-1 hex=${reply.hexPrefix()} ms="))
    }

    @Test fun repeatedZeroReadsStopWithoutRetryingCommand() {
        val usb = FakeUsb(listOf(Step("0c0000000100011000000000", List(9) { byteArrayOf() })))
        PtpUsbClient(usb, logs::add).use { assertFails { it.read(0x1001) } }
        usb.finish()
        assertEquals(1, usb.sent.size)
        assertTrue(logs.last().contains("bulkTransferIN=0"))
    }

    @Test fun rejectsMetadataOverLimitWithoutAllocatingPayload() {
        val usb = FakeUsb(listOf(Step("0c0000000100011000000000", listOf(bytes("0d0040000200011000000000")))))
        PtpUsbClient(usb, logs::add).use { assertFails { it.read(0x1001) } }
        assertTrue(logs.last().contains("limit=4194304"))
    }

    @Test fun rejectsJpegOverLimitAndWrongAdvertisedOrActualSize() {
        for (size in listOf(0L, 0xfffffff1L)) {
            val usb = FakeUsb(listOf(open(), close(1)))
            PtpUsbClient(usb, logs::add).use { c ->
                c.openSession()
                assertFails { c.getObject(42, ByteArrayOutputStream(), size) }
            }
            usb.finish()
        }
        for (actual in listOf(3, 5)) {
            val extra = if (actual < 4) listOf(close(2)) else emptyList()
            val usb = FakeUsb(listOf(open(), readStep("1000000001000910010000002a000000", 0x1009, 1, ByteArray(actual))) + extra)
            PtpUsbClient(usb, logs::add).use { c ->
                c.openSession()
                assertFails { c.getObject(42, ByteArrayOutputStream(), 4) }
            }
        }
    }

    @Test fun forbiddenWritesAndReadsOutsideSessionNeverReachTransport() {
        val usb = FakeUsb(emptyList())
        PtpUsbClient(usb, logs::add).use { c ->
            for (code in listOf(0x1004, 0x1007, 0x1008, 0x100E, 0x1016, 0x9207)) assertFails { c.read(code) }
        }
        assertTrue(usb.sent.isEmpty())
    }

    @Test fun malformedDatasetStillClosesHealthySession() {
        val usb = FakeUsb(listOf(
            readStep("0c0000000100011000000000", 0x1001, 0, deviceInfo()), open(),
            readStep("0c0000000100041001000000", 0x1004, 1, bytes("ffffffff")), close(2)
        ))
        assertFails { PtpUsbClient(usb, logs::add).use { it.inspect() } }
        usb.finish()
    }

    @Test fun closeFailurePropagatesAndOutputFailureDoesNotRetry() {
        val closing = FakeUsb(listOf(open(), Step("0c0000000100031001000000", listOf(response(1, 0x2002)))))
        assertFailsWith<PtpResponseException> { PtpUsbClient(closing, logs::add).use { it.openSession() } }
        closing.finish()
        val usb = FakeUsb(listOf(open(), readStep("1000000001000910010000002a000000", 0x1009, 1, bytes("ffd8ffd9"))))
        val output = object : OutputStream() { override fun write(b: Int) { throw IOException("storage full") } }
        PtpUsbClient(usb, logs::add).use { c ->
            c.openSession()
            assertFailsWith<IOException> { c.getObject(42, output, 4) }
        }
        assertEquals(2, usb.sent.size)
        assertTrue(logs.any { "storage full" in it && "bulkTransferIN=" in it && "hex=" in it })
    }
}
