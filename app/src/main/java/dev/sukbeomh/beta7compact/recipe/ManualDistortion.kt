package dev.sukbeomh.beta7compact.recipe

import kotlin.math.ceil
import kotlin.math.floor

/** Inverse mapping in the original image coordinates. Negative = barrel, positive = pincushion. */
class ManualDistortion(val width: Int, val height: Int, val amount: Float) {
    init {
        require(width > 0 && height > 0)
        require(amount.isFinite() && amount in -1f..1f)
    }
    private val cx = (width - 1) / 2.0
    private val cy = (height - 1) / 2.0
    private val radiusSquared = cx * cx + cy * cy
    private val k = -.18 * amount
    // Keep the inverse map inside the source without stretching edge pixels into missing regions.
    private val scale = if (k <= 0) 1.0 else {
        var low = 0.0; var high = 1.0
        repeat(48) {
            val mid = (low + high) / 2
            if (mid + k * mid * mid * mid > 1) high = mid else low = mid
        }
        low
    }
    data class Point(val x: Double, val y: Double)
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    fun source(x: Double, y: Double): Point {
        if (amount == 0f || radiusSquared == 0.0) return Point(x, y)
        val dx = (x - cx) * scale; val dy = (y - cy) * scale
        val factor = 1 + k * (dx * dx + dy * dy) / radiusSquared
        return Point((cx + dx * factor).coerceIn(0.0, width - 1.0),
            (cy + dy * factor).coerceIn(0.0, height - 1.0))
    }
    fun bounds(left: Int, top: Int, right: Int, bottom: Int): Bounds {
        require(left in 0 until right && right <= width && top in 0 until bottom && bottom <= height)
        // Each component is monotone along its own axis; cross-axis extrema occur at the center or ends.
        val points = listOf(left.toDouble(), right - 1.0, cx.coerceIn(left.toDouble(), right - 1.0)).flatMap { x ->
            listOf(top.toDouble(), bottom - 1.0, cy.coerceIn(top.toDouble(), bottom - 1.0)).map { y -> source(x, y) }
        }
        return Bounds(floor(points.minOf { it.x }).toInt().coerceAtLeast(0),
            floor(points.minOf { it.y }).toInt().coerceAtLeast(0),
            (ceil(points.maxOf { it.x }).toInt() + 1).coerceAtMost(width),
            (ceil(points.maxOf { it.y }).toInt() + 1).coerceAtMost(height))
    }
    fun previewSource(x: Int, y: Int, previewWidth: Int, previewHeight: Int): Point {
        fun full(p: Int, size: Int, original: Int) = if(size==1) (original-1)/2.0 else p.toDouble()*(original-1)/(size-1)
        val point=source(full(x,previewWidth,width),full(y,previewHeight,height))
        return Point(if(width==1) 0.0 else point.x*(previewWidth-1)/(width-1),
            if(height==1) 0.0 else point.y*(previewHeight-1)/(height-1))
    }
    fun renderTile(pixels: IntArray, region: Bounds, left: Int, top: Int, outputWidth: Int, outputHeight: Int,
        checkpoint: () -> Unit = {}): IntArray {
        val rw=region.right-region.left;val rh=region.bottom-region.top
        require(rw>0 && rh>0 && rw.toLong()*rh==pixels.size.toLong())
        require(outputWidth>0 && outputHeight>0 && outputWidth.toLong()*outputHeight<=Int.MAX_VALUE)
        val output=IntArray(outputWidth*outputHeight)
        for(row in 0 until outputHeight) {
            checkpoint()
            for(col in 0 until outputWidth) {
                val point=source((left+col).toDouble(),(top+row).toDouble())
                output[row*outputWidth+col]=sample(pixels,rw,rh,point.x-region.left,point.y-region.top)
            }
        }
        return output
    }
    companion object {
        fun sample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
            val px = x.coerceIn(0.0, width - 1.0); val py = y.coerceIn(0.0, height - 1.0)
            val x0 = px.toInt(); val y0 = py.toInt()
            val x1 = (x0 + 1).coerceAtMost(width - 1); val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fx = px - x0; val fy = py - y0
            var result = 0
            for (shift in 24 downTo 0 step 8) {
                fun channel(xi: Int, yi: Int) = (pixels[yi * width + xi] ushr shift) and 255
                val upper = channel(x0,y0) * (1-fx) + channel(x1,y0) * fx
                val lower = channel(x0,y1) * (1-fx) + channel(x1,y1) * fx
                result = result or (((upper * (1-fy) + lower * fy + .5).toInt().coerceIn(0,255)) shl shift)
            }
            return result
        }
    }
}
