package dev.sukbeomh.beta7compact.remote

import java.math.BigInteger

class UnsupportedSonyPropertyTypeException(val propertyCode: Int, val dataType: Int) :
    IllegalArgumentException("Unsupported Sony property type=$dataType code=$propertyCode")

/** Offline descriptor data. Raw access bytes deliberately do not imply write permission. */
data class SonyPropertyDescriptor(
    val code: Int,
    val dataType: Int,
    val rawGetSet: Int,
    val rawEnabled: Int,
    val defaultValue: BigInteger?,
    val currentValue: BigInteger?,
    val form: SonyPropertyForm,
    val textValues: SonyPropertyTextValues? = null,
)

data class SonyPropertyTextValues(val defaultValue: String, val currentValue: String)

sealed interface SonyPropertyForm {
    data object None : SonyPropertyForm
    data class Range(val minimum: BigInteger, val maximum: BigInteger, val step: BigInteger) : SonyPropertyForm
    data class Enumeration(val values: List<BigInteger>, val secondaryValues: List<BigInteger>? = null) : SonyPropertyForm
    data class TextEnumeration(val values: List<String>, val secondaryValues: List<String>? = null) : SonyPropertyForm
}

/**
 * Offline Sony scalar and PTP string descriptors, including both Sony enumeration lists.
 * Wire layout reference: libgphoto2 31a2f408, ptp-pack.c ptp_unpack_Sony_DPD;
 * snapshot header: ptp.c _ptp_sony_getalldevicepropdesc. No transport or setter is exposed.
 * Arrays and 128-bit values are unsupported.
 */
object SonyPropertyParser {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_PROPERTIES = 4096
    const val STRING_TYPE = 0xffff

    fun descriptor(bytes: ByteArray): SonyPropertyDescriptor = Cursor(bytes).run {
        readDescriptor().also { end() }
    }

    fun snapshot(bytes: ByteArray): List<SonyPropertyDescriptor> = Cursor(bytes).run {
        val count = unsigned(4)
        require(count <= BigInteger.valueOf(MAX_PROPERTIES.toLong())) { "Too many Sony properties" }
        require(unsigned(4) == BigInteger.ZERO) { "Unsupported Sony snapshot header" }
        require(count.toInt() <= remaining / 9) { "Truncated Sony property snapshot" }
        val result = List(count.toInt()) { readDescriptor() }
        require(result.map { it.code }.toSet().size == result.size) { "Duplicate Sony property code" }
        end()
        result
    }

    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0
        val remaining get() = bytes.size - offset
        init { require(bytes.size <= MAX_BYTES) { "Sony property payload too large" } }
        fun unsigned(width: Int): BigInteger = number(width, false)
        private fun number(width: Int, signed: Boolean): BigInteger {
            require(remaining >= width) { "Truncated Sony property at offset=$offset" }
            val value = bytes.copyOfRange(offset, offset + width).reversedArray()
            offset += width
            return if (signed) BigInteger(value) else BigInteger(1, value)
        }
        private fun u8() = unsigned(1).toInt()
        private fun u16() = unsigned(2).toInt()
        private fun string(): String {
            val count = u8()
            if (count == 0) return ""
            require(remaining >= count * 2) { "Truncated Sony property string" }
            val start = offset
            offset += count * 2
            require(bytes[offset - 2] == 0.toByte() && bytes[offset - 1] == 0.toByte()) {
                "Sony property string is missing its terminator"
            }
            return bytes.copyOfRange(start, offset - 2).toString(Charsets.UTF_16LE)
        }

        // A following descriptor begins at 0x5xxx or 0xdxxx. Sony's optional second count is below 0x0200.
        private fun hasSecondaryEnumeration(): Boolean =
            remaining >= 2 && (bytes[offset + 1].toInt() and 0xff) < 2

        private fun <T> values(minimumWidth: Int, readValue: () -> T): List<T> {
            val count = u16()
            require(count <= remaining / minimumWidth) { "Truncated Sony enumeration" }
            return List(count) { readValue() }
        }

        fun end() { require(remaining == 0) { "Unsupported trailing Sony property data" } }
        fun readDescriptor(): SonyPropertyDescriptor {
            val code = u16()
            require(code in 0x5000..0x5fff || code in 0xd000..0xdfff) { "Unsupported Sony property code" }
            val type = u16()
            if (type !in 1..8 && type != STRING_TYPE) throw UnsupportedSonyPropertyTypeException(code, type)
            val access = u8()
            val enabled = u8()
            if (type == STRING_TYPE) {
                val text = SonyPropertyTextValues(string(), string())
                val form = when (val flag = u8()) {
                    0 -> SonyPropertyForm.None
                    2 -> SonyPropertyForm.TextEnumeration(
                        values(1, ::string), if (hasSecondaryEnumeration()) values(1, ::string) else null)
                    else -> throw IllegalArgumentException("Unsupported Sony string property form=$flag")
                }
                return SonyPropertyDescriptor(code, type, access, enabled, null, null, form, text)
            }
            val width = 1 shl ((type - 1) / 2)
            val signed = type % 2 == 1
            fun value() = number(width, signed)
            val default = value()
            val current = value()
            val form = when (val flag = u8()) {
                0 -> SonyPropertyForm.None
                1 -> {
                    val min = value(); val max = value(); val step = value()
                    require(min <= max && step > BigInteger.ZERO) { "Invalid Sony property range" }
                    SonyPropertyForm.Range(min, max, step)
                }
                2 -> SonyPropertyForm.Enumeration(
                    values(width, ::value), if (hasSecondaryEnumeration()) values(width, ::value) else null)
                else -> throw IllegalArgumentException("Unsupported Sony property form=$flag")
            }
            return SonyPropertyDescriptor(code, type, access, enabled, default, current, form)
        }
    }
}
