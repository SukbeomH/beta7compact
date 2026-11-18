package dev.sukbeomh.beta7compact.perspective

import kotlin.math.floor
import kotlin.math.roundToInt

/** Manual photo-local controls, independent of LUT recipes and camera focal length. */
data class LocalWarpConfig(
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val radius: Float = .25f,
    val strength: Float = 0f,
) {
    init {
        require(centerX.isFinite() && centerX in 0f..1f && centerY.isFinite() && centerY in 0f..1f)
        require(radius.isFinite() && radius in .05f.. .5f)
        require(strength.isFinite() && strength in 0f..1f)
    }
}

/**
 * Bounded 2D inverse resampling for a manual shape experiment, not depth reconstruction.
 * Center coordinates use the endpoints of the upright image: (0,0) to (width-1,height-1).
 * Radius is a fraction of min(width-1,height-1); a one-pixel axis produces an identity copy.
 *
 * Within the circle, sourceRadius = outputRadius * (1 + a * (1-r²)²), a <= .15.
 * Sampling farther from the center makes local features contract in the output. The map
 * reaches identity with zero displacement slope at the boundary; its radial derivative
 * stays >= 1 - .8a >= .88, so the unclamped local map cannot fold over.
 * Out-of-image samples use edge clamping; callers should warn about regions near an edge.
 */
object LocalWarp {
    const val MAX_PIXELS = 4_194_304

    fun render(
        input: IntArray,
        width: Int,
        height: Int,
        config: LocalWarpConfig,
        checkpoint: () -> Unit = {},
    ): IntArray {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        require(pixels <= MAX_PIXELS && pixels == input.size.toLong()) { "Image exceeds local warp pixel budget" }
        checkpoint()
        val output = IntArray(input.size)
        val centerX = config.centerX.toDouble() * (width - 1)
        val centerY = config.centerY.toDouble() * (height - 1)
        val radius = config.radius.toDouble() * minOf(width - 1, height - 1)
        val radiusSquared = radius * radius
        val amplitude = .15 * config.strength.toDouble()
        for (y in 0 until height) {
            checkpoint()
            val row = y * width
            if (amplitude == 0.0 || radius == 0.0) {
                input.copyInto(output, row, row, row + width)
                continue
            }
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val normalizedSquared = (dx * dx + dy * dy) / radiusSquared
                if (normalizedSquared >= 1.0) {
                    output[row + x] = input[row + x]
                    continue
                }
                val feather = 1.0 - normalizedSquared
                val scale = 1.0 + amplitude * feather * feather
                val sourceX = (centerX + dx * scale).coerceIn(0.0, (width - 1).toDouble())
                val sourceY = (centerY + dy * scale).coerceIn(0.0, (height - 1).toDouble())
                val x0 = floor(sourceX).toInt()
                val y0 = floor(sourceY).toInt()
                val x1 = minOf(x0 + 1, width - 1)
                val y1 = minOf(y0 + 1, height - 1)
                val ax = sourceX - x0
                val ay = sourceY - y0
                val p00 = input[y0 * width + x0]
                val p10 = input[y0 * width + x1]
                val p01 = input[y1 * width + x0]
                val p11 = input[y1 * width + x1]
                var color = 0
                for (shift in 24 downTo 0 step 8) {
                    val top = ((p00 ushr shift) and 255) * (1.0 - ax) + ((p10 ushr shift) and 255) * ax
                    val bottom = ((p01 ushr shift) and 255) * (1.0 - ax) + ((p11 ushr shift) and 255) * ax
                    val value = (top * (1.0 - ay) + bottom * ay).roundToInt().coerceIn(0, 255)
                    color = color or (value shl shift)
                }
                output[row + x] = color
            }
        }
        return output
    }
}
