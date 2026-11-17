package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.SonyPropertyParser
import dev.sukbeomh.beta7compact.remote.SonyPropertyForm
import java.math.BigInteger
import kotlin.test.*
import org.junit.Test

/** Hand-authored synthetic wire examples, NOT ILCE-7C captures or compatibility evidence. */
class SonyPropertyParserTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val scalar = hex("1e d2 06 00 00 02 ff ff ff ff 00 01 00 00 00")
    private fun snapshot(vararg descriptors: ByteArray): ByteArray =
        byteArrayOf(descriptors.size.toByte(), 0, 0, 0, 0, 0, 0, 0) + descriptors.fold(byteArrayOf()) { a, b -> a + b }

    @Test fun preservesUnsignedValueAndRawAccessWithoutInferringWritability() {
        val d = SonyPropertyParser.descriptor(scalar)
        assertEquals(BigInteger("4294967295"), d.defaultValue)
        assertEquals(BigInteger("256"), d.currentValue)
        assertEquals(0, d.rawGetSet)
        assertEquals(2, d.rawEnabled)
    }
    @Test fun signedRange() {
        val d = SonyPropertyParser.descriptor(hex("10 50 03 00 01 01 00 00 ff ff 01 fe ff 02 00 01 00"))
        assertEquals(BigInteger.valueOf(-1), d.currentValue)
        assertEquals(SonyPropertyForm.Range(BigInteger.valueOf(-2), BigInteger.TWO, BigInteger.ONE), d.form)
    }
    @Test fun enumAndSnapshot() {
        val d = hex("07 50 04 00 01 01 64 00 c8 00 02 02 00 64 00 c8 00")
        assertEquals(SonyPropertyForm.Enumeration(listOf(BigInteger.valueOf(100), BigInteger.valueOf(200))), SonyPropertyParser.descriptor(d).form)
        assertEquals(2, SonyPropertyParser.snapshot(snapshot(scalar, d)).size)
    }
    @Test fun everyTruncatedPrefixFails() {
        for (fixture in listOf(scalar, hex("10 50 03 00 01 01 00 00 ff ff 01 fe ff 02 00 01 00"), hex("07 50 04 00 01 01 64 00 c8 00 02 02 00 64 00 c8 00"))) {
            for (i in fixture.indices) assertFailsWith<IllegalArgumentException>("length=$i") { SonyPropertyParser.descriptor(fixture.copyOf(i)) }
        }
        val packet = snapshot(scalar)
        for (i in packet.indices) assertFailsWith<IllegalArgumentException> { SonyPropertyParser.snapshot(packet.copyOf(i)) }
    }
    @Test fun unknownTypeFormAndTrailingBytesFailClosed() {
        for (bad in listOf(scalar.copyOf().apply { this[2] = 0xff.toByte() }, scalar.copyOf().apply { this[lastIndex] = 3 }, scalar + byteArrayOf(0, 0))) {
            assertFailsWith<IllegalArgumentException> { SonyPropertyParser.descriptor(bad) }
        }
    }
    @Test fun malformedCountsAndDuplicatePropertiesFail() {
        for (bad in listOf(snapshot(scalar).apply { this[0] = 2 }, snapshot(scalar).apply { this[3] = 0x7f }, snapshot(scalar).apply { this[4] = 1 }, snapshot(scalar, scalar))) {
            assertFailsWith<IllegalArgumentException> { SonyPropertyParser.snapshot(bad) }
        }
        assertFailsWith<IllegalArgumentException> { SonyPropertyParser.descriptor(hex("07 50 04 00 01 01 64 00 c8 00 02 ff ff")) }
    }
    @Test fun uint64RetainsAllBits() {
        val d = SonyPropertyParser.descriptor(hex("1e d2 08 00 01 01 ff ff ff ff ff ff ff ff 00 00 00 00 00 00 00 00 00"))
        assertEquals(BigInteger("18446744073709551615"), d.defaultValue)
    }
    @Test fun invalidRangeRejected() {
        assertFailsWith<IllegalArgumentException> { SonyPropertyParser.descriptor(hex("10 50 03 00 01 01 00 00 00 00 01 02 00 01 00 01 00")) }
        assertFailsWith<IllegalArgumentException> { SonyPropertyParser.descriptor(hex("10 50 03 00 01 01 00 00 00 00 01 00 00 01 00 00 00")) }
    }
    @Test fun allIntegerWidthsPreserveSignBoundaries() {
        for (type in 1..8) {
            val width = 1 shl ((type - 1) / 2)
            val bits = width * 8
            val signed = type % 2 == 1
            val minimum = if (signed) BigInteger.ONE.shiftLeft(bits - 1).negate() else BigInteger.ZERO
            val maximum = BigInteger.ONE.shiftLeft(if (signed) bits - 1 else bits).subtract(BigInteger.ONE)
            fun littleEndian(value: BigInteger): ByteArray {
                val unsigned = if (value.signum() < 0) value.add(BigInteger.ONE.shiftLeft(bits)) else value
                return ByteArray(width) { unsigned.shiftRight(it * 8).toByte() }
            }
            val packet = byteArrayOf(0x1e, 0xd2.toByte(), type.toByte(), 0, 1, 1) +
                littleEndian(minimum) + littleEndian(maximum) + byteArrayOf(0)
            val parsed = SonyPropertyParser.descriptor(packet)
            assertEquals(minimum, parsed.defaultValue, "type=$type minimum")
            assertEquals(maximum, parsed.currentValue, "type=$type maximum")
        }
    }
    @Test fun valuesOutsideEnumArePreserved() {
        val parsed = SonyPropertyParser.descriptor(hex("07 50 04 00 01 01 2c 01 90 01 02 01 00 64 00"))
        assertEquals(BigInteger.valueOf(300), parsed.defaultValue)
        assertEquals(BigInteger.valueOf(400), parsed.currentValue)
        assertEquals(SonyPropertyForm.Enumeration(listOf(BigInteger.valueOf(100))), parsed.form)
    }
    @Test fun emptySnapshotAndEnumerationAreValid() {
        assertEquals(emptyList(), SonyPropertyParser.snapshot(ByteArray(8)))
        val parsed = SonyPropertyParser.descriptor(hex("07 50 04 00 01 01 64 00 c8 00 02 00 00"))
        assertEquals(SonyPropertyForm.Enumeration(emptyList()), parsed.form)
    }
    @Test fun oversizedPayloadIsRejectedBeforeParsing() {
        val oversized = ByteArray(1024 * 1024 + 1)
        assertFailsWith<IllegalArgumentException> { SonyPropertyParser.descriptor(oversized) }
        assertFailsWith<IllegalArgumentException> { SonyPropertyParser.snapshot(oversized) }
    }
    @Test fun unsupportedStringArrayAnd128BitTypesAreExplicitlyRejected() {
        for (type in listOf(0xffff, 0x4006, 9, 10)) {
            val packet = scalar.copyOf().apply {
                this[2] = type.toByte()
                this[3] = (type ushr 8).toByte()
            }
            assertFailsWith<IllegalArgumentException>("type=$type") { SonyPropertyParser.descriptor(packet) }
        }
    }

}
