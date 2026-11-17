package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.SonyExtendedInfoParser
import dev.sukbeomh.beta7compact.remote.SonyProtocolVersion
import dev.sukbeomh.beta7compact.remote.UnsupportedSonyProtocolVersionException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SonyExtendedInfoParserTest {
    // Hand-authored synthetic wire examples, not captures from an ILCE-7C.
    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()

    private val mode2 = hex("c8 00 02 00 00 00 07 50 1e d2 02 00 00 00 c1 d2 c2 d2")
    private val mode3 = hex("2c 01 01 00 00 00 ff ff 01 00 00 00 00 80")

    @Test fun parsesMode2WithoutMergingPropertiesAndControls() {
        val info = SonyExtendedInfoParser.parse(mode2)
        assertEquals(SonyProtocolVersion.MODE_2, info.protocolVersion)
        assertEquals(0x00c8, info.advertisedVersion)
        assertEquals(listOf(0x5007, 0xd21e), info.propertyCodes)
        assertEquals(listOf(0xd2c1, 0xd2c2), info.controlCodes)
    }

    @Test fun parsesMode3AndPreservesUnsignedUnknownCodes() {
        val info = SonyExtendedInfoParser.parse(mode3)
        assertEquals(SonyProtocolVersion.MODE_3, info.protocolVersion)
        assertEquals(0x012c, info.advertisedVersion)
        assertEquals(listOf(0xffff), info.propertyCodes)
        assertEquals(listOf(0x8000), info.controlCodes)
    }

    @Test fun acceptsEmptyArraysButNotAnEmptyResponse() {
        val info = SonyExtendedInfoParser.parse(hex("c8 00 00 00 00 00 00 00 00 00"))
        assertEquals(emptyList(), info.propertyCodes)
        assertEquals(emptyList(), info.controlCodes)
        assertFailsWith<IllegalArgumentException> { SonyExtendedInfoParser.parse(byteArrayOf()) }
    }

    @Test fun preservesDuplicateCodesInAdvertisedOrder() {
        val info = SonyExtendedInfoParser.parse(hex("c8 00 02 00 00 00 07 50 07 50 00 00 00 00"))
        assertEquals(listOf(0x5007, 0x5007), info.propertyCodes)
    }

    @Test fun refusesToInferMode2ForUnknownVersions() {
        for (version in listOf("00 00", "c9 00", "2d 01", "ff ff")) {
            val bytes = hex("$version 00 00 00 00 00 00 00 00")
            val failure = assertFailsWith<UnsupportedSonyProtocolVersionException> {
                SonyExtendedInfoParser.parse(bytes)
            }
            assertEquals((bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8),
                failure.advertisedVersion)
        }
    }

    @Test fun rejectsEveryTruncatedPrefixAndTrailingData() {
        for (fixture in listOf(mode2, mode3)) {
            for (length in fixture.indices) {
                assertFailsWith<IllegalArgumentException>("prefix length=$length") {
                    SonyExtendedInfoParser.parse(fixture.copyOf(length))
                }
            }
            assertFailsWith<IllegalArgumentException> { SonyExtendedInfoParser.parse(fixture + 0) }
        }
    }

    @Test fun rejectsUnsignedCountOverflowAndMissingElementsInEitherArray() {
        for (count in listOf("ff ff ff ff", "00 00 00 80", "01 00 00 00")) {
            assertFailsWith<IllegalArgumentException> {
                SonyExtendedInfoParser.parse(hex("c8 00 $count"))
            }
            assertFailsWith<IllegalArgumentException> {
                SonyExtendedInfoParser.parse(hex("c8 00 00 00 00 00 $count"))
            }
        }
    }

    @Test fun rejectsCountThatConsumesTheFollowingArrayHeader() {
        assertFailsWith<IllegalArgumentException> {
            SonyExtendedInfoParser.parse(hex("c8 00 02 00 00 00 00 00 00 00"))
        }
    }

    @Test fun requiresLittleEndianVersionAndCounts() {
        assertFailsWith<UnsupportedSonyProtocolVersionException> {
            SonyExtendedInfoParser.parse(hex("00 c8 00 00 00 00 00 00 00 00"))
        }
        assertFailsWith<IllegalArgumentException> {
            SonyExtendedInfoParser.parse(hex("c8 00 00 00 00 01 07 50 00 00 00 00"))
        }
    }

    @Test fun rejectsPayloadAboveOneMiBBeforeDecodingItsContents() {
        val payload = ByteArray(1024 * 1024 + 1)
        val failure = assertFailsWith<IllegalArgumentException> { SonyExtendedInfoParser.parse(payload) }
        assertEquals("Sony extended info payload too large", failure.message)
    }
}
