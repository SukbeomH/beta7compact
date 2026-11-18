package dev.sukbeomh.beta7compact.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SonyProtocolVersion(val advertisedValue: Int) {
    MODE_2(0x00c8), MODE_3(0x012c)
}

data class SonyExtendedInfo(
    val protocolVersion: SonyProtocolVersion,
    val propertyCodes: List<Int>,
    val controlCodes: List<Int>
) {
    val advertisedVersion: Int get() = protocolVersion.advertisedValue
}

class UnsupportedSonyProtocolVersionException(val advertisedVersion: Int) :
    IllegalArgumentException("Unsupported Sony protocol version=$advertisedVersion")

/** Offline GetExtDeviceInfo payload decoder; does not negotiate or establish a camera session. */
object SonyExtendedInfoParser {
    private const val MAX_BYTES = 1024 * 1024
    // Format reference: libgphoto2 31a2f4089f19aec6caca7920b03e3eb7a251c5b6,
    // ptp.c:4397-4420 and ptp-pack.c:242-270. A supported format is not camera compatibility.
    fun parse(payload: ByteArray): SonyExtendedInfo {
        require(payload.size <= MAX_BYTES) { "Sony extended info payload too large" }
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(data.remaining() >= 2) { "Sony extended info missing version" }
        val advertisedVersion = data.short.toInt() and 0xffff
        val protocol = SonyProtocolVersion.entries.firstOrNull { it.advertisedValue == advertisedVersion }
            ?: throw UnsupportedSonyProtocolVersionException(advertisedVersion)

        fun codes(): List<Int> {
            require(data.remaining() >= 4) { "Sony extended info missing array count" }
            val count = data.int.toLong() and 0xffffffffL
            require(count <= data.remaining() / 2) { "Sony extended info array exceeds payload" }
            return List(count.toInt()) { data.short.toInt() and 0xffff }
        }

        val properties = codes()
        val controls = codes()
        require(!data.hasRemaining()) { "Sony extended info trailing bytes" }
        return SonyExtendedInfo(protocol, properties, controls)
    }
}
