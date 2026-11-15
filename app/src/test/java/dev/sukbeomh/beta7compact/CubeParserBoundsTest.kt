package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.CubeParser
import org.junit.Test
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import kotlin.test.*

class CubeParserBoundsTest {
    private val maxBytes = 32 * 1024 * 1024
    private val tooLarge = ".cube 파일이 너무 큽니다"
    private val valid = "LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(8)

    // Port of /tmp/beta7-review-m2/ReviewProbe.kt: generate arbitrarily large
    // input on demand, and fail the test before an unbounded parser can OOM.
    private class GeneratedReader(
        private val prefix: String, private val pattern: String,
        private val total: Long, private val budget: Long
    ) : Reader() {
        var consumed = 0L
            private set
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (consumed == total) return -1
            val count = minOf(length.toLong(), total - consumed).toInt()
            check(consumed + count <= budget) { "Reader exceeded read-ahead budget: ${consumed + count}" }
            repeat(count) { i ->
                val p = consumed + i
                buffer[offset + i] = if (p < prefix.length) prefix[p.toInt()]
                    else pattern[((p - prefix.length) % pattern.length).toInt()]
            }
            consumed += count
            return count
        }
        override fun close() = Unit
    }

    private class GeneratedInput(
        private val prefix: ByteArray, private val pattern: ByteArray,
        private val total: Long, private val budget: Long
    ) : InputStream() {
        var consumed = 0L
            private set
        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (consumed == total) return -1
            val count = minOf(length.toLong(), total - consumed).toInt()
            check(consumed + count <= budget) { "InputStream exceeded read-ahead budget: ${consumed + count}" }
            repeat(count) { i ->
                val p = consumed + i
                buffer[offset + i] = if (p < prefix.size) prefix[p.toInt()]
                    else pattern[((p - prefix.size) % pattern.size).toInt()]
            }
            consumed += count
            return count
        }
    }

    @Test fun hugeUnterminatedTitleCommentWhitespaceAndDataStopWithinReaderBudget() {
        val cases = listOf("TITLE \"" to "x", "#" to "#", "" to " ", "LUT_3D_SIZE 2\n" to "0 ")
        for ((prefix, pattern) in cases) for (total in listOf(8192L, 16L shl 20, 512L shl 20, 1L shl 30)) {
            val lineStart = prefix.lastIndexOf('\n') + 1
            val budget = lineStart + 4097L + 1024
            val reader = GeneratedReader(prefix, pattern, total, budget)
            val error = assertFailsWith<IllegalArgumentException> { CubeParser.parse(reader) }
            assertEquals(tooLarge, error.message)
            assertTrue(reader.consumed in (lineStart + 4097L)..budget)
            assertTrue(reader.consumed < total, "Must reject before EOF: $prefix / $total")
        }
    }

    @Test fun hugeUnterminatedLinesStopWithinInputStreamDecoderBudget() {
        val cases = listOf("TITLE \"" to "한", "#" to "#", "" to " ", "LUT_3D_SIZE 2\n" to "0 ")
        for ((prefix, pattern) in cases) for (total in listOf(32768L, 16L shl 20, 512L shl 20, 1L shl 30)) {
            val lineStart = prefix.lastIndexOf('\n') + 1
            // UTF-8 can use three bytes per character here; the decoder reads
            // ahead by at most its 8192-byte input buffer.
            val bytesPerChar = pattern.toByteArray().size / pattern.length
            val budget = lineStart + 4097L * bytesPerChar + 8192
            val input = GeneratedInput(prefix.toByteArray(), pattern.toByteArray(), total, budget)
            val error = assertFailsWith<IllegalArgumentException> { CubeParser.parse(input) }
            assertEquals(tooLarge, error.message)
            assertTrue(input.consumed in (lineStart + 4097L)..budget)
            assertTrue(input.consumed < total, "Must reject before EOF: $prefix / $total")
        }
    }

    @Test fun exactLineLimitAndLfCrLfCrAndEofRemainAccepted() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val text = ("#" + "x".repeat(4095) + "\n" + valid + "#" + "x".repeat(4095))
                .replace("\n", ending)
            assertEquals(2, CubeParser.parse(StringReader(text)).size)
            assertEquals(2, CubeParser.parse(text.byteInputStream()).size)
        }
    }

    @Test fun exactTitleWhitespaceAndDataLineLimitsRemainAccepted() {
        val title = "x".repeat(4088)
        val text = "TITLE \"$title\"\n" + " ".repeat(4096) + "\n" +
            valid.replaceFirst("0 0 0", "0 0 0" + " ".repeat(4091))
        assertEquals(title.take(160), CubeParser.parse(StringReader(text)).title)
        assertEquals(title.take(160), CubeParser.parse(text.byteInputStream()).title)
    }

    @Test fun lineLimitCountsRawCommentAndWhitespaceBeforeTrimming() {
        for (line in listOf("#" + "x".repeat(4096), " ".repeat(4097),
            "TITLE \"" + "x".repeat(4089) + "\"", "0 0 0" + " ".repeat(4092))) {
            assertEquals(4097, line.length)
            for (ending in listOf("", "\n", "\r\n", "\r")) {
                assertEquals(tooLarge, assertFailsWith<IllegalArgumentException> {
                    CubeParser.parse(StringReader(valid + line + ending))
                }.message)
            }
        }
    }

    @Test fun byteCapIncludesUtf8CommentsAndAcceptsExactly32MiB() {
        for (pattern in listOf("#\n", "#한\n")) {
            val bytes = pattern.toByteArray()
            val padding = (maxBytes - valid.length) % bytes.size
            val prefix = (valid + "\n".repeat(padding)).toByteArray()
            val exact = GeneratedInput(prefix, bytes, maxBytes.toLong(), maxBytes.toLong())
            assertEquals(2, CubeParser.parse(exact).size)
            assertEquals(maxBytes.toLong(), exact.consumed)

            val oversized = GeneratedInput(prefix, bytes, 1L shl 30, maxBytes + 1L)
            assertEquals(tooLarge, assertFailsWith<IllegalArgumentException> {
                CubeParser.parse(oversized)
            }.message)
            assertEquals(maxBytes + 1L, oversized.consumed)
        }
    }

    @Test fun readerCharacterCapIncludesLineTerminatorsAndStopsBeforeEof() {
        val prefix = valid + "\n".repeat((maxBytes - valid.length) % 2)
        val exact = GeneratedReader(prefix, "#\n", maxBytes.toLong(), maxBytes.toLong())
        assertEquals(2, CubeParser.parse(exact).size)
        assertEquals(maxBytes.toLong(), exact.consumed)
        val oversized = GeneratedReader(prefix, "#\n", 1L shl 30, maxBytes + 1L)
        assertEquals(tooLarge, assertFailsWith<IllegalArgumentException> {
            CubeParser.parse(oversized)
        }.message)
        assertEquals(maxBytes + 1L, oversized.consumed)
    }

    @Test fun inputStreamPreserves65CubedTitleCommentsAndDomains() {
        val prefix = "\uFEFFTITLE \"정상 # LUT\" # comment\r\nLUT_3D_SIZE 65\r\n" +
            "DOMAIN_MIN -1 0 0\r\nDOMAIN_MAX 1 2 1\r\n"
        val point = "0 0 0\r\n"
        val length = prefix.toByteArray().size + point.length * 65L * 65 * 65
        val input = GeneratedInput(prefix.toByteArray(), point.toByteArray(), length, length)
        val lut = CubeParser.parse(input)
        assertEquals(65, lut.size)
        assertEquals("정상 # LUT", lut.title)
        assertContentEquals(floatArrayOf(-1f, 0f, 0f), lut.domainMin)
        assertContentEquals(floatArrayOf(1f, 2f, 1f), lut.domainMax)
        assertTrue(lut.values.all { it == 0f })
        assertEquals(length, input.consumed)
    }
}
