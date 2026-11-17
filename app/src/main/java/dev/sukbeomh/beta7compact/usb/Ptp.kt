package dev.sukbeomh.beta7compact.usb

import dev.sukbeomh.beta7compact.core.CameraObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
fun hex(value: Int): String = "0x%04X".format(value)
fun ByteArray.hexPrefix(): String = take(64).joinToString(" ") { "%02X".format(it.toInt() and 255) }

class PtpReader(private val bytes: ByteArray) {
    private val data = le(bytes)
    fun u16(): Int { need(2); return data.short.toInt() and 65535 }
    fun u32(): Int { need(4); return data.int }
    fun skip(n: Int) { need(n); data.position(data.position() + n) }
    private fun need(n: Int) {
        require(n >= 0 && data.remaining() >= n) {
            "PTP truncated offset=${data.position()} need=$n remaining=${data.remaining()} hex=${bytes.hexPrefix()}"
        }
    }
    fun string(): String {
        need(1)
        val count = data.get().toInt() and 255
        if (count == 0) return ""
        need(count * 2)
        val chars = CharArray(count) { u16().toChar() }
        require(chars.last() == '\u0000') { "PTP string missing terminator" }
        return String(chars, 0, count - 1)
    }
    fun array(width: Int): IntArray {
        val n = u32().toLong() and 0xffffffffL
        require(width == 2 || width == 4)
        require(n <= data.remaining() / width) { "PTP array invalid count=$n hex=${bytes.hexPrefix()}" }
        return IntArray(n.toInt()) { if (width == 2) u16() else u32() }
    }
    fun end() { require(!data.hasRemaining()) { "PTP trailing bytes=${data.remaining()}" } }
}

data class DeviceInfo(val manufacturer: String, val model: String, val version: String, val operations: IntArray,
                      val properties: IntArray = intArrayOf())
data class ObjectInfo(val handle: Int, val storage: Int, val format: Int, val size: Long,
                      val name: String, val captured: String, val modified: String, val parent: Int = 0) {
    val isJpeg: Boolean get() = format == 0x3801
    val date: String get() = captured.ifEmpty { modified }
    // Shared by raw transfer and the MtpDevice adapter's read-only metadata pass.
    fun cameraObject() = CameraObject(handle, storage, name, size, CameraObject.ptpTime(date), parent, date)
}

object Ptp {
    const val OK = 0x2001
    fun deviceInfo(data: ByteArray): DeviceInfo {
        val r = PtpReader(data)
        r.u16(); r.u32(); r.u16(); r.string(); r.u16()
        val operations = r.array(2)
        r.array(2) // Events
        val properties = r.array(2)
        repeat(2) { r.array(2) } // Capture and image formats
        val result = DeviceInfo(r.string(), r.string(), r.string(), operations, properties)
        r.string() // Serial number deliberately excluded from diagnostic output.
        r.end()
        return result
    }
    fun ids(data: ByteArray): IntArray = PtpReader(data).run { array(4).also { end() } }
    fun objectInfo(handle: Int, data: ByteArray): ObjectInfo {
        val r = PtpReader(data)
        val storage = r.u32()
        val format = r.u16()
        r.u16()
        val size = r.u32().toLong() and 0xffffffffL
        r.skip(26)
        val parent = r.u32()
        r.skip(10)
        val result = ObjectInfo(handle, storage, format, size, r.string(), r.string(), r.string(), parent)
        r.string(); r.end()
        return result
    }
    // Same camera clock: compare capture time, then filename and unsigned handle.
    fun latest(objects: List<ObjectInfo>): ObjectInfo? = objects.filter { it.isJpeg }
        .maxWithOrNull(compareBy<ObjectInfo> { it.date }.thenBy { it.name }
            .thenBy { it.handle.toLong() and 0xffffffffL })
}

/** Standard PTP USB container codec, independent of Android's MTP transport. */
data class PtpContainer(val type: Int, val code: Int, val transaction: Int, val payload: ByteArray) {
    fun encode(): ByteArray = buffer(12 + payload.size).putInt(12 + payload.size)
        .putShort(type.toShort()).putShort(code.toShort()).putInt(transaction).put(payload).array()
    companion object {
        fun decode(bytes: ByteArray): PtpContainer {
            require(bytes.size >= 12) { "PTP container header truncated" }
            val b = le(bytes)
            require(b.int == bytes.size) { "PTP container length mismatch" }
            val type = b.short.toInt() and 65535
            require(type in 1..4) { "PTP container type=$type" }
            return PtpContainer(type, b.short.toInt() and 65535, b.int, bytes.copyOfRange(12, bytes.size))
        }
    }
}

class PtpResponseException(val code: Int) : java.io.IOException("PTP response=${hex(code)}")
