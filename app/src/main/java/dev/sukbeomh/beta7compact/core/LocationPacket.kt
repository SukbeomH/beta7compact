package dev.sukbeomh.beta7compact.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId

/** Independently implemented from the public ILCE7M3ExternalGps protocol table. */
object LocationPacket {
    fun encode(latitude: Double, longitude: Double, instant: Instant, zone: ZoneId,
               includeZone: Boolean): ByteArray {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val utc = instant.atZone(ZoneOffset.UTC)
        require(utc.year in 1..65535)
        val bytes = ByteBuffer.allocate(if (includeZone) 95 else 91).order(ByteOrder.BIG_ENDIAN)
        bytes.putShort((bytes.capacity() - 2).toShort())
        bytes.put(byteArrayOf(8, 2, 0xfc.toByte(), if (includeZone) 3 else 0, 0, 0, 0x10, 0x10, 0x10))
        bytes.putInt((latitude * 10_000_000).toInt()).putInt((longitude * 10_000_000).toInt())
        bytes.putShort(utc.year.toShort()).put(utc.monthValue.toByte()).put(utc.dayOfMonth.toByte())
            .put(utc.hour.toByte()).put(utc.minute.toByte()).put(utc.second.toByte())
        if (includeZone) {
            bytes.position(91)
            val standard = zone.rules.getStandardOffset(instant).totalSeconds / 60
            val dst = zone.rules.getDaylightSavings(instant).toMinutes().toInt()
            bytes.putShort(standard.toShort()).putShort(dst.toShort())
        }
        return bytes.array()
    }
    fun zoneRequired(config: ByteArray): Boolean {
        require(config.size >= 6) { "위치 서비스 설정 응답이 짧습니다" }
        return config[4].toInt() and 0x02 != 0
    }
    fun enabled(status: ByteArray): Boolean? = when {
        status.contentEquals(byteArrayOf(3, 1, 2, 1)) -> true
        status.contentEquals(byteArrayOf(3, 1, 2, 0)) -> false
        else -> null
    }
}
