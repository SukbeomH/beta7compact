package dev.sukbeomh.beta7compact.recipe

import kotlin.math.*

object CpuEngine {
    fun slog3(v: Float): Float = if (v >= 171.2102946929f/1023f)
        (10.0.pow((v*1023.0-420.0)/261.5)*0.19-0.01).toFloat()
        else (v*1023f-95f)*0.01125f/(171.2102946929f-95f)
    fun linear(v: Float): Float = if (v <= 0.04045f) v/12.92f else ((v+0.055f)/1.055f).pow(2.4f)
    fun srgb(v: Float): Float = if (v <= 0.0031308f) 12.92f*v else 1.055f*v.pow(1f/2.4f)-0.055f
    fun input(rgb: FloatArray): FloatArray {
        val v = FloatArray(3) { slog3(rgb[it]) }
        val m = arrayOf(floatArrayOf(1.6269474f,-0.5401385f,-0.0868089f),
            floatArrayOf(-0.1785155f,1.4179409f,-0.2394254f),floatArrayOf(-0.0444361f,-0.19591997f,1.2403561f))
        return FloatArray(3) { r -> srgb((0..2).sumOf { c -> (m[r][c]*v[c]).toDouble() }.toFloat()).coerceIn(0f,1f) }
    }
    fun noise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 69069
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 65535)/65535f-0.5f
    }
    /** Coordinate-independent part, also used by the 33-cube baker. */
    fun color(rgb: FloatArray,lut: Lut,recipe: Recipe): FloatArray {
        var base=if(recipe.inputTransform==InputTransform.NONE) rgb else input(rgb)
        fun look(c: FloatArray): FloatArray {
            val sampled=lut.sample(c)
            return FloatArray(3) { (c[it]+(sampled[it]-c[it])*recipe.strength).coerceIn(0f,1f) }
        }
        if(recipe.colorVersion==1) base=look(base) // Preserve immutable M2 snapshots.
        val gains=floatArrayOf(2f.pow(recipe.wb.temperature*.5f),2f.pow(-recipe.wb.tint*.5f),2f.pow(-recipe.wb.temperature*.5f))
        val encoded=FloatArray(3) { srgb(linear(base[it])*gains[it]*2f.pow(recipe.ev)).coerceIn(0f,1f) }
        val edited=ColorMath.apply(encoded,recipe.edits)
        return if(recipe.colorVersion==1) edited else look(edited)
    }
    fun pixel(rgb: FloatArray, lut: Lut, recipe: Recipe, x: Float, y: Float, width: Int, height: Int): FloatArray {
        val encoded = color(rgb,lut,recipe)
        val luma = encoded[0]*0.2126f+encoded[1]*0.7152f+encoded[2]*0.0722f
        val n = noise(floor(x/recipe.grain.size).toInt(),floor(y/recipe.grain.size).toInt(),recipe.grain.seed)
        val g = n*recipe.grain.amount*0.2f*(0.25f+0.75f*sqrt(luma*(1-luma)))
        val dx = (x+0.5f)/width*2-1; val dy = (y+0.5f)/height*2-1
        val vignette = 1-recipe.vignette*((dx*dx+dy*dy)*0.5f).coerceIn(0f,1f)
        return FloatArray(3) { ((encoded[it]+g)*vignette).coerceIn(0f,1f) }
    }
}
