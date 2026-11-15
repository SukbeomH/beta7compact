package dev.sukbeomh.beta7compact.recipe

import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Encoded sRGB, red fastest, RGB interleaved. Optional CUBE domain is stored in a sidecar. */
data class Lut(val size: Int, val values: FloatArray, val title: String = "사용자 LUT",
    val domainMin: FloatArray = floatArrayOf(0f,0f,0f), val domainMax: FloatArray = floatArrayOf(1f,1f,1f)) {
    init {
        require(domainMin.size == 3 && domainMax.size == 3 && domainMin.indices.all {
            domainMin[it].isFinite() && domainMax[it].isFinite() && (domainMax[it]-domainMin[it]).isFinite() && domainMax[it] > domainMin[it]
        }) { "DOMAIN 범위가 잘못되었습니다" }
        require(size in 2..65 && values.size == size * size * size * 3 && values.all { it.isFinite() && it in 0f..1f }) { "LUT 값 또는 크기가 잘못되었습니다" } }
    fun sample(rgb: FloatArray): FloatArray {
        require(rgb.size == 3 && rgb.all { it.isFinite() }) { "입력 색상이 유한수가 아닙니다" }
        val p = FloatArray(3) { ((rgb[it] - domainMin[it]) / (domainMax[it] - domainMin[it])).coerceIn(0f, 1f) * (size - 1) }
        val lo = IntArray(3) { floor(p[it]).toInt().coerceAtMost(size - 2) }
        val t = FloatArray(3) { p[it] - lo[it] }
        return FloatArray(3) { c ->
            var sum = 0f
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                val w = (if (r == 0) 1 - t[0] else t[0]) * (if (g == 0) 1 - t[1] else t[1]) * (if (b == 0) 1 - t[2] else t[2])
                sum += values[3 * (lo[0] + r + size * (lo[1] + g) + size * size * (lo[2] + b)) + c] * w
            }
            sum
        }
    }
    fun encode(): ByteArray = ByteBuffer.allocate(16 + values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(66, 55, 76, 84)); putShort(1); putShort(size.toShort()); putInt(3); putInt(0)
        values.forEach { putShort(Math.rint(it.toDouble() * 65535).toInt().toShort()) }
    }.array()
    companion object {
        fun decode(bytes: ByteArray, title: String = "사용자 LUT"): Lut {
            require(bytes.size >= 16) { "LUT 헤더가 잘못되었습니다" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == 0x544c3742 && b.short.toInt() == 1) { "지원하지 않는 LUT 형식입니다" }
            val n = b.short.toInt()
            require(n in 2..65 && b.int == 3 && b.int == 0 && bytes.size == 16 + n * n * n * 6) { "LUT 길이 또는 헤더가 잘못되었습니다" }
            return Lut(n, FloatArray(n * n * n * 3) { (b.short.toInt() and 65535) / 65535f }, title)
        }
        fun read(input: InputStream, title: String = "사용자 LUT"): Lut {
            val bytes = input.takeBounded(16 + 65 * 65 * 65 * 6 + 1)
            return decode(bytes, title)
        }
        fun identity(n: Int = 2) = Lut(n, FloatArray(n*n*n*3) { i ->
            val p = i / 3
            (when (i % 3) { 0 -> p % n; 1 -> p / n % n; else -> p / n / n }).toFloat() / (n - 1)
        })
    }
}

object CubeParser {
    /** Reject 1D, mixed, non-finite, duplicate headers, unsupported directives and trailing data. */
    fun parse(reader: Reader): Lut {
        var size = 0; var title = "사용자 LUT"; var count = 0
        var values: FloatArray? = null
        var min = floatArrayOf(0f,0f,0f); var max = floatArrayOf(1f,1f,1f)
        val seen = mutableSetOf<String>()
        var chars = 0
        reader.buffered().forEachLine { raw ->
            chars += raw.length + 1
            require(chars <= 32 * 1024 * 1024 && raw.length <= 4096) { ".cube 파일이 너무 큽니다" }
            // # inside a quoted TITLE is retained.
            var quoted = false
            val line = raw.takeWhile { if (it == '"') quoted = !quoted; it != '#' || quoted }.trim().removePrefix("\uFEFF").trim()
            if (line.isNotEmpty()) {
                val parts = line.split(Regex("\\s+"))
                val key = parts[0]
                when (key) {
                    "TITLE", "LUT_3D_SIZE", "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        require(count == 0 && seen.add(key)) { ".cube 헤더가 중복되거나 위치가 잘못되었습니다" }
                        when (key) {
                            "TITLE" -> { require(Regex("TITLE\\s+\"[^\"\\r\\n]*\"").matches(line)) { "TITLE 형식이 잘못되었습니다" }; title = line.substringAfter('"').substringBeforeLast('"').take(160) }
                            "LUT_3D_SIZE" -> { size = parts.getOrNull(1)?.toIntOrNull() ?: 0; require(parts.size == 2 && size in 2..65) { "LUT 크기는 2~65여야 합니다" }; values = FloatArray(size*size*size*3) }
                            else -> {
                                require(parts.size == 4) { "DOMAIN 값이 잘못되었습니다" }
                                val v = FloatArray(3) { parts[it+1].toFloatOrNull()?.takeIf { n -> n.isFinite() } ?: throw IllegalArgumentException("DOMAIN 값이 유한수가 아닙니다") }
                                if (key == "DOMAIN_MIN") min = v else max = v
                            }
                        }
                    }
                    else -> {
                        val data = values ?: throw IllegalArgumentException("LUT_3D_SIZE가 없거나 지원하지 않는 지시문입니다")
                        require(parts.size == 3 && count + 3 <= data.size) { "LUT 행 개수가 잘못되었습니다" }
                        for (p in parts) data[count++] = p.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                            ?: throw IllegalArgumentException("LUT 값은 유한한 0~1이어야 합니다")
                    }
                }
            }
        }
        require(min.indices.all { max[it] > min[it] }) { "DOMAIN_MAX는 DOMAIN_MIN보다 커야 합니다" }
        val data = values ?: throw IllegalArgumentException("LUT_3D_SIZE가 없습니다")
        require(count == data.size) { "LUT 행 개수가 부족합니다" }
        return Lut(size, data, title, min, max)
    }
}

private fun InputStream.takeBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer,0,minOf(buffer.size,limit-output.size()))
        if (count < 0) break
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
