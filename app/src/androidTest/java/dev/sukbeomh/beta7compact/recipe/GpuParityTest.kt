package dev.sukbeomh.beta7compact.recipe

import android.graphics.Bitmap
import android.graphics.Color
import android.test.InstrumentationTestCase
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real GLES3/EGL, no Robolectric or JVM replacement. Run connectedDebugAndroidTest. */
@Suppress("DEPRECATION")
class GpuParityTest : InstrumentationTestCase() {
    fun testAllBuiltinsCpuGpuWithFullPipelineAndBothInputModes() {
        val assets=instrumentation.targetContext.assets
        val manifest=assets.open("luts/manifest.json").bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("luts")
        assertEquals(27,manifest.length())
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        for(y in 0..15) for(x in 0..15) bitmap.setPixel(x,y,Color.rgb(x*17,y*17,((x*37+y*19)%256)))
        try {
            for(i in 0 until manifest.length()) {
                val entry=manifest.getJSONObject(i)
                val lut=assets.open("luts/${entry.getString("path")}").use { Lut.read(it) }
                GpuEngine(lut).use { gpu ->
                    for(transform in InputTransform.entries) {
                        val recipe=Recipe(entry.getString("id"),.73f,transform,WhiteBalance(.2f,-.3f),.4f,Grain(3f,.6f,-718),.4f)
                        val result=gpu.render(bitmap,recipe,x=511,y=255,fullWidth=6000,fullHeight=4000)
                        try {
                            for(y in 0..15) for(x in 0..15) {
                                val c=bitmap.getPixel(x,y)
                                val p=floatArrayOf(Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f)
                                val ref=CpuEngine.pixel(p,lut,recipe,x+511f,y+255f,6000,4000)
                                val out=result.getPixel(x,y);val actual=intArrayOf(Color.red(out),Color.green(out),Color.blue(out))
                                for(channel in 0..2) assertTrue("CPU/GPU diff LUT=$i mode=$transform channel=$channel",abs(actual[channel]-(ref[channel]*255).roundToInt())<=2)
                            }
                        } finally { result.recycle() }
                    }
                }
            }
        } finally { bitmap.recycle() }
    }
    fun testNonDefaultCubeDomainCpuGpuParity() {
        val lut=Lut.identity().copy(domainMin=floatArrayOf(.49f,-1f,0f),domainMax=floatArrayOf(.51f,2f,1f))
        val source=Bitmap.createBitmap(8,1,Bitmap.Config.ARGB_8888)
        for(x in 0..7) source.setPixel(x,0,Color.rgb(124+x,32*x,15*x))
        try {
            GpuEngine(lut).use { gpu ->
                val out=gpu.render(source,Recipe())
                try { for(x in 0..7) {
                    val c=source.getPixel(x,0);val p=floatArrayOf(Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f)
                    val expected=CpuEngine.pixel(p,lut,Recipe(),x.toFloat(),0f,8,1)
                    val actual=out.getPixel(x,0)
                    assertTrue(abs(Color.red(actual)-(expected[0]*255).roundToInt())<=2)
                    assertTrue(abs(Color.green(actual)-(expected[1]*255).roundToInt())<=2)
                    assertTrue(abs(Color.blue(actual)-(expected[2]*255).roundToInt())<=2)
                } } finally {out.recycle()}
            }
        } finally {source.recycle()}
    }
    fun testTilesAreSeamlessAndAllEightOrientationsPreservePixels() {
        val bitmap=Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(71,113,179))
        val r=Recipe(grain=Grain(3.5f,1f,42),vignette=.5f)
        try {
            GpuEngine(Lut.identity()).use { gpu ->
                val full=gpu.render(bitmap,r)
                try {
                    for(y in 0 until 30 step 10) for(x in 0 until 40 step 10) {
                        val tile=Bitmap.createBitmap(bitmap,x,y,10,10)
                        val out=gpu.render(tile,r,x,y,40,30)
                        try { for(j in 0..9) for(i in 0..9) assertEquals(full.getPixel(x+i,y+j),out.getPixel(i,j)) }
                        finally {out.recycle();if(tile!==bitmap) tile.recycle()}
                    }
                } finally {full.recycle()}
            }
            for(y in 0 until 30) for(x in 0 until 40) bitmap.setPixel(x,y,Color.rgb(x*5,y*7,20))
            for(o in 1..8) {
                val out=PhotoRenderer.upright(bitmap,o)
                assertEquals(if(o>=5) 30 else 40,out.width);assertEquals(if(o>=5) 40 else 30,out.height)
                for(y in 0 until 30) for(x in 0 until 40) {
                    val point=when(o) {
                        2 -> 39-x to y;3 -> 39-x to 29-y;4 -> x to 29-y;5 -> y to x
                        6 -> 29-y to x;7 -> 29-y to 39-x;8 -> y to 39-x;else -> x to y
                    }
                    assertEquals(bitmap.getPixel(x,y),out.getPixel(point.first,point.second))
                }
                if(out!==bitmap) out.recycle()
            }
        } finally {bitmap.recycle()}
    }
}
