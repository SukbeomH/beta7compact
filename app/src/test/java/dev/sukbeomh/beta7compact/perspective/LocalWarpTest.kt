package dev.sukbeomh.beta7compact.perspective

import org.junit.Test
import kotlin.math.abs
import kotlin.test.*

class LocalWarpTest {
    private fun gradient(width: Int, height: Int) = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        (255 shl 24) or ((x * 2).coerceAtMost(255) shl 16) or ((y * 2).coerceAtMost(255) shl 8) or 37
    }
    private fun red(pixel: Int) = (pixel ushr 16) and 255

    @Test fun zeroStrengthCopiesDecodedPixelsWithoutModifyingSource() {
        val input = gradient(101, 81)
        val original = input.copyOf()
        val result = LocalWarp.render(input, 101, 81, LocalWarpConfig())
        assertContentEquals(input, result)
        assertContentEquals(original, input)
        assertNotSame(input, result)
    }

    @Test fun maximumStrengthLeavesCenterAndEverythingOutsideCircleUnchanged() {
        val input = gradient(101, 101)
        val result = LocalWarp.render(input, 101, 101, LocalWarpConfig(radius = .4f, strength = 1f))
        assertEquals(input[50 * 101 + 50], result[50 * 101 + 50])
        for (y in 0..100) for (x in 0..100) {
            if ((x - 50) * (x - 50) + (y - 50) * (y - 50) > 1601) {
                assertEquals(input[y * 101 + x], result[y * 101 + x], "outside ($x,$y)")
            }
        }
    }

    @Test fun inverseSamplingContractsFeaturesAndRetainsMirrorSymmetry() {
        val input = gradient(101, 101)
        val config = LocalWarpConfig(radius = .4f, strength = 1f)
        val result = LocalWarp.render(input, 101, 101, config)
        assertTrue(red(result[50 * 101 + 70]) > red(input[50 * 101 + 70]))
        assertTrue(red(result[50 * 101 + 30]) < red(input[50 * 101 + 30]))
        for (x in 0..100) {
            assertTrue(abs(red(result[50 * 101 + x]) + red(result[50 * 101 + 100 - x]) - 200) <= 1)
            assertEquals(255, result[50 * 101 + x] ushr 24)
            if (x > 0) assertTrue(red(result[50 * 101 + x]) >= red(result[50 * 101 + x - 1]), "inverse map folded at $x")
        }
    }

    @Test fun samplingPreservesConstantColorsEvenForEdgeRegions() {
        val input = IntArray(53 * 47) { 0x807A35E1.toInt() }
        for (cx in listOf(0f, .5f, 1f)) for (cy in listOf(0f, .5f, 1f)) {
            assertContentEquals(input, LocalWarp.render(input, 53, 47,
                LocalWarpConfig(centerX = cx, centerY = cy, radius = .5f, strength = 1f)))
        }
    }

    @Test fun onePixelAxesAreIdentityAndSmallImagesAreSafe() {
        for ((w, h) in listOf(1 to 1, 1 to 9, 9 to 1)) {
            val input = gradient(w, h)
            assertContentEquals(input, LocalWarp.render(input, w, h, LocalWarpConfig(strength = 1f)))
        }
        for (w in 2..4) for (h in 2..4) {
            val input = gradient(w, h)
            assertEquals(input.size, LocalWarp.render(input, w, h, LocalWarpConfig(strength = 1f)).size)
        }
    }

    @Test fun invalidConfigAndImageDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(centerX = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(centerY = 1.1f) }
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(radius = .01f) }
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(radius = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(strength = -1f) }
        assertFailsWith<IllegalArgumentException> { LocalWarpConfig(strength = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LocalWarp.render(IntArray(0), 0, 1, LocalWarpConfig()) }
        assertFailsWith<IllegalArgumentException> { LocalWarp.render(IntArray(3), 2, 2, LocalWarpConfig()) }
    }

    @Test fun pixelBudgetRejectsOverflowSizedDimensionsBeforeAllocationOrCheckpoint() {
        var checkpointCalled = false
        assertFailsWith<IllegalArgumentException> {
            LocalWarp.render(IntArray(1), Int.MAX_VALUE, Int.MAX_VALUE, LocalWarpConfig()) { checkpointCalled = true }
        }
        assertFalse(checkpointCalled)
        assertFailsWith<IllegalArgumentException> {
            LocalWarp.render(IntArray(1), 4096, 4096, LocalWarpConfig())
        }
    }

    @Test fun checkpointsStopBothIdentityAndWarpedJobsWithoutReturningPartialImage() {
        class Stop : RuntimeException()
        for (strength in listOf(0f, 1f)) {
            var calls = 0
            val input = gradient(101, 101)
            val original = input.copyOf()
            assertFailsWith<Stop> {
                LocalWarp.render(input, 101, 101, LocalWarpConfig(strength = strength)) {
                    calls++
                    if (calls == 4) throw Stop()
                }
            }
            assertEquals(4, calls)
            assertContentEquals(original, input)
        }
    }
}
