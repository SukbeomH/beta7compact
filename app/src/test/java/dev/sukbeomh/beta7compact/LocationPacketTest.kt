package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.LocationPacket
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class LocationPacketTest {
    private fun bytes(value: String) = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun publicProtocolExampleMatchesAllNinetyFiveBytes() {
        // Public documentation vector only; no device or personal location data.
        val documented = bytes("005d0802fc0300001010100bf79e5e41c385a707e40b0504022a" + "00".repeat(65) + "01e00000")
        val input = ByteBuffer.wrap(documented)
        val lat = input.getInt(11) / 1e7; val lon = input.getInt(15) / 1e7
        assertContentEquals(documented, LocationPacket.encode(lat, lon, Instant.parse("2020-11-05T04:02:42Z"), ZoneId.of("Asia/Shanghai"), true))
    }
    @Test fun olderConfigurationOmitsTimezoneAndCorrectsLengthAndFlag() {
        val packet = LocationPacket.encode(0.0, 0.0, Instant.EPOCH, ZoneId.of("UTC"), false)
        assertEquals(91, packet.size); assertEquals(89, ByteBuffer.wrap(packet).short.toInt()); assertEquals(0, packet[5])
        assertTrue(packet.sliceArray(26..90).all { it == 0.toByte() })
    }
    @Test fun signedCoordinatesNegativeZoneAndDstUseSeparateOffsets() {
        val packet = LocationPacket.encode(-1.0, -2.0, Instant.parse("2020-07-01T00:00:00Z"), ZoneId.of("America/New_York"), true)
        val data = ByteBuffer.wrap(packet)
        assertEquals(-10000000, data.getInt(11)); assertEquals(-20000000, data.getInt(15))
        assertEquals(-300, data.getShort(91).toInt()); assertEquals(60, data.getShort(93).toInt())
        assertEquals(0, packet[23]) // UTC clock, never phone-local clock.
    }
    @Test fun rejectsNonFiniteAndOutOfRangeCoordinates() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0, -91.0))
            assertFails { LocationPacket.encode(bad, 0.0, Instant.EPOCH, ZoneId.of("UTC"), true) }
        assertFails { LocationPacket.encode(0.0, 181.0, Instant.EPOCH, ZoneId.of("UTC"), true) }
    }
    @Test fun configBitIsHex02AndUnknownNotificationsNeverEnableWriting() {
        assertTrue(LocationPacket.zoneRequired(bytes("0610009c0200")))
        assertFalse(LocationPacket.zoneRequired(bytes("0610009c0400")))
        assertFails { LocationPacket.zoneRequired(byteArrayOf(1)) }
        assertEquals(true, LocationPacket.enabled(bytes("03010201")))
        assertEquals(false, LocationPacket.enabled(bytes("03010200")))
        assertNull(LocationPacket.enabled(bytes("0301")))
    }
}
