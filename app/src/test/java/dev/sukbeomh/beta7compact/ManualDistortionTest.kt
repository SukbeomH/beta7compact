package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.*
import kotlin.test.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ManualDistortionTest {
    @Test fun zeroPreservesEveryPixel() {
        val w=37;val h=23;val input=IntArray(w*h) { it*1234567 }
        val plan=ManualDistortion(w,h,0f)
        assertContentEquals(input,plan.renderTile(input,ManualDistortion.Bounds(0,0,w,h),0,0,w,h))
    }
    @Test fun previewCoordinatesMatchFullResolutionIncludingPortraitAndSinglePixel() {
        for((w,h) in listOf(6000 to 4000,4000 to 6000,1 to 4000)) for(a in listOf(-1f,0f,1f)) {
            val plan=ManualDistortion(w,h,a);val pw=37;val ph=29
            for(y in 0 until ph) for(x in 0 until pw) {
                val p=plan.previewSource(x,y,pw,ph)
                val full=plan.source(x.toDouble()*(w-1)/(pw-1),y.toDouble()*(h-1)/(ph-1))
                assertEquals(if(w==1) 0.0 else full.x*(pw-1)/(w-1),p.x,1e-10)
                assertEquals(full.y*(ph-1)/(h-1),p.y,1e-10)
            }
        }
    }
    @Test fun signsProduceOppositeRadialEffectsWithStableCenter() {
        for(amount in listOf(-1f,1f)) {
            val plan=ManualDistortion(101,101,amount)
            assertEquals(ManualDistortion.Point(50.0,50.0),plan.source(50.0,50.0))
            val middle=plan.source(75.0,50.0).x
            val edge=plan.source(75.0,100.0).x
            if(amount<0) assertTrue(edge>middle) else assertTrue(edge<middle)
        }
    }
    @Test fun mappingIsSymmetricMonotoneAndHasNoMissingBorders() {
        for((w,h) in listOf(101 to 79,79 to 101,1 to 81,81 to 1,1 to 1)) for(a in listOf(-1f,-.4f,0f,.4f,1f)) {
            val plan=ManualDistortion(w,h,a)
            for(y in 0 until h) {
                var previous=-1.0
                for(x in 0 until w) {
                    val p=plan.source(x.toDouble(),y.toDouble())
                    val mirror=plan.source(w-1.0-x,h-1.0-y)
                    assertTrue(p.x in 0.0..w-1.0 && p.y in 0.0..h-1.0)
                    assertTrue(p.x>=previous);previous=p.x
                    assertEquals(w-1.0,p.x+mirror.x,1e-9)
                    assertEquals(h-1.0,p.y+mirror.y,1e-9)
                }
            }
        }
    }
    @Test fun sourceRegionsIncludeBilinearNeighborsAtTileBoundaries() {
        val w=1031;val h=779
        for(a in listOf(-1f,-.3f,.3f,1f)) {
            val plan=ManualDistortion(w,h,a)
            for(top in 0 until h step 257) for(left in 0 until w step 257) {
                val right=minOf(left+257,w);val bottom=minOf(top+257,h)
                val b=plan.bounds(left,top,right,bottom)
                for(y in top until bottom step 3) for(x in left until right step 3) {
                    val p=plan.source(x.toDouble(),y.toDouble())
                    assertTrue(p.x>=b.left-1e-9 && p.x<=b.right-1+1e-9)
                    assertTrue(p.y>=b.top-1e-9 && p.y<=b.bottom-1+1e-9)
                }
            }
        }
    }
    @Test fun tiledRenderingEqualsWholeImageWithoutSeams() {
        val w=61;val h=43;val input=IntArray(w*h) { i -> 0xff000000.toInt() or ((i*73) and 0xffffff) }
        for(a in listOf(-1f,-.25f,.25f,1f)) {
            val plan=ManualDistortion(w,h,a)
            val expected=plan.renderTile(input,ManualDistortion.Bounds(0,0,w,h),0,0,w,h)
            val actual=IntArray(w*h)
            for(y in 0 until h step 13) for(x in 0 until w step 17) {
                val tw=minOf(17,w-x);val th=minOf(13,h-y);val b=plan.bounds(x,y,x+tw,y+th)
                val rw=b.right-b.left;val rh=b.bottom-b.top
                val region=IntArray(rw*rh) { i -> input[(b.top+i/rw)*w+b.left+i%rw] }
                val tile=plan.renderTile(region,b,x,y,tw,th)
                for(row in 0 until th) tile.copyInto(actual,(y+row)*w+x,row*tw,(row+1)*tw)
            }
            assertContentEquals(expected,actual)
        }
    }
    @Test fun bilinearSamplingAndCancellationAreObservable() {
        val pixels=intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xffffffff.toInt(),0xff000000.toInt())
        assertEquals(0xff808080.toInt(),ManualDistortion.sample(pixels,2,2,.5,.5))
        var rows=0
        assertFailsWith<CancellationException> {
            ManualDistortion(20,20,.5f).renderTile(IntArray(400),ManualDistortion.Bounds(0,0,20,20),0,0,20,20) {
                if(++rows==3) throw CancellationException()
            }
        }
        assertEquals(3,rows)
    }
    @Test fun recipePersistenceHistoryAndOldDefaultsPreserveGeometry() {
        assertEquals(0f,Recipe.fromJson(Recipe().toJson()).distortion)
        assertEquals(0f,Recipe.fromJson(Recipe(version=1).toJson()).distortion)
        val a=Recipe(version=3,distortion=-.72f,edits=ColorEdits(contrast=20f))
        assertEquals(a,Recipe.fromJson(a.toJson()))
        val shared=SharedRecipe("왜곡 테스트",a)
        assertEquals(shared,RecipeShare.decode(RecipeShare.encode(shared)))
        val history=RecipeHistory(Recipe());history.set(a)
        assertEquals(0f,history.undo().distortion);assertEquals(a,history.redo())
        assertEquals(a.copy(distortion=0f),Recipe.fromJson(a.copy(distortion=0f).toJson()))
    }
    @Test fun malformedOrIncompatibleGeometryIsRejected() {
        for(a in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1.01f,1.01f)) {
            assertFailsWith<IllegalArgumentException> {ManualDistortion(10,10,a)}
            assertFailsWith<IllegalArgumentException> {Recipe(version=3,distortion=a)}
        }
        assertFails {Recipe(distortion=.1f)}
        assertFails {Recipe.fromJson(Recipe(version=3,distortion=.2f).toJson().replace("\"version\":3","\"version\":2"))}
        assertFails {Recipe.fromJson(Recipe(version=3).toJson().replace(",\"distortion\":0.0",""))}
    }
    @Test fun legacyColorOrderSurvivesGeometryAndAdjustmentDetectionDoesNotThrow() {
        val legacy=Recipe(version=1,ev=.6f,strength=.4f)
        val warped=legacy.copy(version=3,distortion=-.4f)
        assertEquals(1,warped.colorVersion)
        assertEquals(warped,Recipe.fromJson(warped.toJson()))
        val lut=Lut(2,FloatArray(24) { i -> if(i%3==0) .2f else .8f },"nonidentity")
        assertContentEquals(CpuEngine.color(floatArrayOf(.2f,.4f,.7f),lut,legacy),
            CpuEngine.color(floatArrayOf(.2f,.4f,.7f),lut,warped))
        assertTrue(dev.sukbeomh.beta7compact.ui.isAdjusted(Recipe(version=3,distortion=.3f)))
        assertFalse(dev.sukbeomh.beta7compact.ui.isAdjusted(Recipe(version=3)))
    }
    @Test fun cubeContainsColorOnlyAndV3ColorMatchesV2() {
        val r=Recipe(ev=.5f,edits=ColorEdits(contrast=25f))
        val v3=r.copy(version=3,distortion=.8f)
        assertContentEquals(CpuEngine.color(floatArrayOf(.2f,.4f,.7f),Lut.identity(),r),
            CpuEngine.color(floatArrayOf(.2f,.4f,.7f),Lut.identity(),v3))
        assertContentEquals(RecipeBaker.bake(r,Lut.identity()).values,RecipeBaker.bake(v3,Lut.identity()).values)
    }
}
