package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.SonyEventObservation
import dev.sukbeomh.beta7compact.remote.SonyEventParser
import org.junit.Test
import kotlin.test.*

class SonyEventParserTest {
    // Hand-authored synthetic USB event bytes, not an ILCE-7C capture.
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val property = hex("10 00 00 00 04 00 03 c2 ff ff ff ff 1e d2 00 00")
    private val objectAdded = hex("10 00 00 00 04 00 01 c2 00 00 00 00 01 c0 ff ff")

    @Test fun decodesUnsignedTransactionAndParameterWithoutInventingCorrelation() {
        val event = SonyEventParser.parse(property)
        assertEquals(SonyEventParser.PROPERTY_CHANGED, event.code)
        assertEquals(0xffffffffL, event.transactionId)
        assertEquals(listOf(0xd21eL), event.parameters)
        assertEquals(listOf(0xffffc001L), SonyEventParser.parse(objectAdded).parameters)
    }

    @Test fun preservesUnknownCodesAndZeroOrThreeParameters() {
        val empty = SonyEventParser.parse(hex("0c 00 00 00 04 00 fe cf 00 00 00 80"))
        assertEquals(0xcffe, empty.code)
        assertEquals(0x80000000L, empty.transactionId)
        assertTrue(empty.parameters.isEmpty())
        val full = SonyEventParser.parse(hex("18 00 00 00 04 00 fe cf 00 00 00 00 00 00 00 00 ff ff ff ff 00 00 00 80"))
        assertEquals(listOf(0L, 0xffffffffL, 0x80000000L), full.parameters)
    }

    @Test fun rejectsEveryTruncationAndTrailingBytes() {
        for (size in property.indices) assertFailsWith<IllegalArgumentException>("size=$size") {
            SonyEventParser.parse(property.copyOf(size))
        }
        assertFailsWith<IllegalArgumentException> { SonyEventParser.parse(property + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { SonyEventParser.parse(property + ByteArray(4)) }
    }

    @Test fun rejectsNonEventsMisalignedAndOversizedParameters() {
        for (type in listOf(0, 1, 2, 3, 5)) {
            assertFailsWith<IllegalArgumentException> {
                SonyEventParser.parse(property.copyOf().apply { this[4] = type.toByte() })
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SonyEventParser.parse(hex("0d 00 00 00 04 00 03 c2 00 00 00 00 00"))
        }
        assertFailsWith<IllegalArgumentException> {
            SonyEventParser.parse(ByteArray(28).apply { this[0] = 28; this[4] = 4 })
        }
        assertFailsWith<IllegalArgumentException> {
            SonyEventParser.parse(property.copyOf().apply { this[3] = 0x80.toByte() })
        }
    }

    @Test fun repeatedAndReorderedEventsOnlyInvalidateSnapshots() {
        val start = SonyEventObservation.start(7, 100, 50)
        val changed = start.observe(SonyEventParser.parse(property), 7, 101)
        val duplicate = changed.observe(SonyEventParser.parse(property), 7, 101)
        assertEquals(changed, duplicate)
        val objectThenProperty = start.observe(SonyEventParser.parse(objectAdded), 7, 101)
            .observe(SonyEventParser.parse(property), 7, 102)
        val propertyThenObject = changed.observe(SonyEventParser.parse(objectAdded), 7, 102)
        assertEquals(objectThenProperty, propertyThenObject)
        assertTrue(propertyThenObject.propertiesNeedRefresh)
        assertTrue(propertyThenObject.objectsNeedRefresh)
        assertFalse(propertyThenObject.expired)
    }

    @Test fun delayedOldGenerationCannotInvalidateNewWindow() {
        val window = SonyEventObservation.start(8, 100, 50)
        val result = window.observe(SonyEventParser.parse(property), 7, 110)
        assertFalse(result.propertiesNeedRefresh)
        assertFalse(result.objectsNeedRefresh)
        assertFalse(result.expired)
    }

    @Test fun deadlineExpiresWithoutEventsAndLateEventsCannotReviveWindow() {
        val window = SonyEventObservation.start(8, 100, 50)
        assertFalse(window.advance(149).expired)
        assertTrue(window.advance(150).expired)
        val late = window.observe(SonyEventParser.parse(objectAdded), 8, 150)
        assertTrue(late.expired)
        assertFalse(late.objectsNeedRefresh)
        assertTrue(late.advance(151).expired)
    }

    @Test fun capturedAndUnknownEventsDoNotImplySuccessOrRefresh() {
        val window = SonyEventObservation.start(8, 100, 50)
        for (packet in listOf(
            hex("0c 00 00 00 04 00 06 c2 00 00 00 00"),
            hex("0c 00 00 00 04 00 fe cf 00 00 00 00"),
        )) {
            assertEquals(window, window.observe(SonyEventParser.parse(packet), 8, 100))
        }
    }

    @Test fun validatesClockAndDeadlineArithmetic() {
        for (timeout in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SonyEventObservation.start(1, 1, timeout) }
        }
        assertFailsWith<IllegalArgumentException> { SonyEventObservation.start(1, -1, 10) }
        assertFailsWith<IllegalArgumentException> { SonyEventObservation.start(1, 100, 10).advance(99) }
    }
}
