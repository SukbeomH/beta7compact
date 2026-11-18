package dev.sukbeomh.beta7compact.core

/**
 * Sony camera manufacturer data (after company id 0x012D), read from the public ILCE7M3ExternalGps
 * protocol notes: `03 00` camera type, two version bytes, two ASCII model-code bytes, then
 * three-byte groups of tag, status bitmap and an unused byte until tag 0.
 */
data class SonyAdvertisement(val version: Int, val model: String, val tags: Map<Int, Int>) {
    private fun bit(tag: Int, mask: Int): Boolean? = tags[tag]?.let { it and mask != 0 }
    val pairingSupported get() = bit(0x22, 0x80)
    /** The notes call this bit "Paired"; other public notes read it as the pairing screen being open. */
    val pairingBit get() = bit(0x22, 0x40)
    val locationSupported get() = bit(0x22, 0x20)
    val locationEnabled get() = bit(0x22, 0x10)
    val poweredOn get() = bit(0x21, 0x40)

    /** One line for the screen and the shared report. Holds no address, name or per-device bytes. */
    val summary: String get() {
        fun word(value: Boolean?, yes: String, no: String) = when (value) { true -> yes; false -> no; null -> "표시 없음" }
        val status = tags[0x22]?.let { "0x%02X".format(it) } ?: "없음"
        return "버전 $version · 모델 코드 ${model.ifEmpty { "없음" }} · 상태(0x22) $status · " +
            "페어링 ${word(pairingSupported, "지원", "미지원")}/${word(pairingBit, "비트 켜짐", "비트 꺼짐")} · " +
            "위치 ${word(locationSupported, "지원", "미지원")}/${word(locationEnabled, "켜짐", "꺼짐")}"
    }

    companion object {
        fun parse(data: ByteArray): SonyAdvertisement? {
            if (data.size < 6 || data[0] != 3.toByte() || data[1] != 0.toByte()) return null
            val version = (data[2].toInt() and 0xff) or ((data[3].toInt() and 0xff) shl 8)
            val model = data.copyOfRange(4, 6).filter { it in 0x20..0x7e }.map { it.toInt().toChar() }.joinToString("")
            val tags = LinkedHashMap<Int, Int>()
            var i = 6
            while (i + 1 < data.size && tags.size < 16) {
                val tag = data[i].toInt() and 0xff
                if (tag == 0) break
                tags[tag] = data[i + 1].toInt() and 0xff
                i += 3
            }
            return SonyAdvertisement(version, model, tags)
        }
    }
}

/** Android silently returns nothing after five scan starts in thirty seconds, so starts are counted here first. */
class ScanLimiter(private val limit: Int = 5, private val windowMs: Long = 30_000L) {
    private val starts = ArrayDeque<Long>()
    /** Milliseconds to wait before [needed] more scans may start; 0 when they may start now. */
    @Synchronized fun waitFor(now: Long, needed: Int): Long {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        val excess = starts.size + needed - limit
        return if (excess <= 0) 0 else (starts[(excess - 1).coerceAtMost(starts.lastIndex)] + windowMs - now).coerceAtLeast(1)
    }
    @Synchronized fun started(now: Long) { starts.addLast(now) }
}

/** What one location-link attempt saw, kept so that a failed attempt on the real camera still leaves evidence. */
data class LinkTrace(
    val step: String = "시작 전", val failed: String? = null, val mtu: Int? = null,
    val characteristics: List<String> = emptyList(), val pairingService: Boolean? = null,
    val config: String? = null, val statuses: List<String> = emptyList(), val unlock: String = "해당 없음", val sent: Int = 0,
) {
    val text: String get() = listOf(
        "단계: $step" + (failed?.let { " · 실패: $it" } ?: ""),
        "MTU: ${mtu ?: "미확인"}",
        "위치 서비스 특성: ${characteristics.joinToString(" ").ifEmpty { "미확인" }}",
        "페어링 서비스(EE00): ${when (pairingService) { true -> "있음"; false -> "없음"; null -> "미확인" }}",
        "설정(DD21): ${config ?: "미확인"}",
        "상태 알림(DD01): ${statuses.joinToString(" / ").ifEmpty { "받지 못함" }}",
        "잠금 해제(DD30/DD31): $unlock",
        "위치 전송 성공: ${sent}회",
    ).joinToString("\n")
    companion object {
        fun hex(bytes: ByteArray, limit: Int = 16): String =
            bytes.take(limit).joinToString(" ") { "%02X".format(it) } + if (bytes.size > limit) " …(${bytes.size}바이트)" else ""
    }
}
