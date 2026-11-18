package dev.sukbeomh.beta7compact.recipe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect

/** Geometry precedes the existing color pipeline; orientation is applied afterwards in both paths. */
object DistortionRenderer {
    fun preview(input: Bitmap, fullWidth: Int, fullHeight: Int, amount: Float, checkpoint: () -> Unit): Bitmap {
        if (amount == 0f) return input
        val plan = ManualDistortion(fullWidth, fullHeight, amount)
        val w = input.width; val h = input.height
        val pixels = IntArray(w*h); input.getPixels(pixels,0,w,0,0,w,h)
        val output = IntArray(w*h)
        for (y in 0 until h) {
            checkpoint()
            for (x in 0 until w) {
                val source = plan.previewSource(x,y,w,h)
                output[y*w+x] = ManualDistortion.sample(pixels,w,h,source.x,source.y)
            }
        }
        return Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888)
    }
    fun tile(decoder: BitmapRegionDecoder, plan: ManualDistortion, x: Int, y: Int, width: Int, height: Int,
        options: BitmapFactory.Options, checkpoint: () -> Unit): Bitmap {
        val bounds = plan.bounds(x,y,x+width,y+height)
        val region = decoder.decodeRegion(Rect(bounds.left,bounds.top,bounds.right,bounds.bottom),options)
            ?: error("왜곡 조절 원본 영역을 읽을 수 없습니다")
        try {
            val pixels = IntArray(region.width*region.height)
            region.getPixels(pixels,0,region.width,0,0,region.width,region.height)
            val output = plan.renderTile(pixels,bounds,x,y,width,height,checkpoint)
            return Bitmap.createBitmap(output,width,height,Bitmap.Config.ARGB_8888)
        } finally { region.recycle() }
    }
}
