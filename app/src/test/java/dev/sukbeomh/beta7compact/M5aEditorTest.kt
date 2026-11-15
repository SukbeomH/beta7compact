package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.zip.Deflater
import kotlin.math.*
import kotlin.test.*

class M5aEditorTest {
    private fun edits()=ColorEdits(highlights=-18f,shadows=12f,contrast=15f,saturation=-10f,vibrance=20f,
        curves=Curves(ToneCurve(listOf(CurvePoint(0f,0f),CurvePoint(.3f,.25f),CurvePoint(.7f,.76f),CurvePoint(1f,1f)))),
        hsl=List(8) { HslBand((it-4)*3f,(it-4)*2f,(it-4)*1f) },split=SplitTone(220f,15f,45f,12f,5f))
    @Test fun monotoneCubicsHaveZeroOvershootIncludingFlatAndReversingSegments() {
        val rng=Random(841)
        repeat(150) { iteration ->
            val n=2+rng.nextInt(15)
            val values=List(n) { if(iteration%3==0) rng.nextInt(4)/3f else rng.nextFloat() }.let { if(iteration%2==0) it.sorted() else it }
            val curve=ToneCurve(values.mapIndexed { i,y -> CurvePoint(i/(n-1f),y) })
            for(i in 0 until n-1) {
                val a=curve.points[i];val b=curve.points[i+1];var previous=a.y
                for(k in 0..100) {
                    val y=curve.sample((a.x+(b.x-a.x)*k/100).coerceIn(a.x,b.x))
                    assertTrue(y in min(a.y,b.y)..max(a.y,b.y),"overshoot")
                    if(b.y>=a.y) assertTrue(y+1e-6f>=previous) else assertTrue(y-1e-6f<=previous)
                    previous=y
                }
                assertEquals(a.y,curve.sample(a.x),1e-6f)
            }
        }
        assertFails { ToneCurve(List(17) { CurvePoint(it/16f,it/16f) }) }
        assertFails { ToneCurve(listOf(CurvePoint(0f,0f),CurvePoint(0f,1f))) }
        assertFails { ToneCurve(listOf(CurvePoint(0f,Float.NaN),CurvePoint(1f,1f))) }
    }
    @Test fun hslWeightsAreContinuousAndPartitionUnityAcrossEveryBoundaryAndRedWrap() {
        for(h in 0..3600) {
            val weights=ColorMath.weights(h/10f)
            assertEquals(1f,weights.sum(),1e-6f);assertTrue(weights.all { it in 0f..1f })
        }
        for(center in ColorMath.centers) {
            val before=ColorMath.weights(center-.001f);val after=ColorMath.weights(center+.001f)
            for(i in 0..7) assertEquals(before[i],after[i],2e-5f)
            val a=ColorMath.apply(ColorMath.rgb(center-.001f,.7f,.5f),edits())
            val b=ColorMath.apply(ColorMath.rgb(center+.001f,.7f,.5f),edits())
            for(i in 0..2) assertEquals(a[i],b[i],.0002f)
        }
        for(h in 0..359 step 7) {
            val rgb=ColorMath.rgb(h.toFloat(),.7f,.4f);val neutral=ColorMath.apply(rgb,ColorEdits())
            for(i in 0..2) assertEquals(rgb[i],neutral[i],1e-6f)
        }
    }
    @Test fun v1LoadsWithoutNewFieldsAndPreservesM2OrderWhileV2RoundTripsAllEdits() {
        val legacy=Recipe(lutId="identity",wb=WhiteBalance(.3f,-.4f),ev=.7f,version=1)
        assertFalse(legacy.toJson().contains("edits"));assertEquals(legacy,Recipe.fromJson(legacy.toJson()))
        val lut=Lut(2,FloatArray(24) { if(it%3==0) .4f else .2f })
        val p=floatArrayOf(.2f,.4f,.6f);val look=lut.sample(p)
        val out=CpuEngine.color(p,lut,legacy)
        val gains=floatArrayOf(2f.pow(.15f),2f.pow(.2f),2f.pow(-.15f))
        for(c in 0..2) assertEquals(CpuEngine.srgb(CpuEngine.linear(look[c])*gains[c]*2f.pow(.7f)),out[c],1e-6f)
        val named=legacy.copy(requiredLutName="필요 LUT")
        assertEquals(named,Recipe.fromJson(named.toJson()))
        val current=Recipe(edits=edits());assertEquals(2,current.version);assertEquals(current,Recipe.fromJson(current.toJson()))
        assertFails { Recipe.fromJson(current.toJson().replace("\"highlights\":-18.0","\"highlights\":101")) }
        assertFails { Recipe.fromJson(current.toJson().replace("\"highlights\":-18.0","\"highlights\":1e999")) }
        assertFails { Recipe.fromJson("{".repeat(17000)) }
        assertFails { HslBand(Float.NaN) };assertFails { SplitTone(balance=101f) }
    }
    @Test fun shareRoundTripsUnicodeAndUserLutReferenceWithoutLutData() {
        val recipe=Recipe(lutId="user-00000000-0000-0000-0000-000000000000",edits=edits())
        val value=SharedRecipe("여름 ☀ 레시피",recipe,"직접 만든 LUT")
        val code=RecipeShare.encode(value)
        assertTrue(code.startsWith("b7c1:"));assertEquals(value,RecipeShare.decode(code))
        assertEquals(SharedRecipe("기본",Recipe()),RecipeShare.decode(RecipeShare.encode(SharedRecipe("기본",Recipe()))))
        assertEquals("여름 (3)",RecipeShare.uniqueName("여름",listOf("여름","여름 (2)")))
        assertTrue(RecipeShare.uniqueName("x".repeat(80),listOf("x".repeat(80))).length<=80)
    }
    @Test fun maximumCurveCountsAndAllFieldsSurviveShareAndQr() {
        val random=Random(8891)
        val curves=List(4) { ToneCurve(List(16) { i -> CurvePoint(i/15f,random.nextFloat()) }) }
        val full=Recipe(lutId="user-00000000-0000-0000-0000-000000000000",strength=.1234567f,
            wb=WhiteBalance(-1f,1f),ev=5f,grain=Grain(32f,1f,Int.MAX_VALUE),vignette=1f,
            edits=ColorEdits(100f,-100f,100f,-100f,100f,Curves(curves[0],curves[1],curves[2],curves[3]),
                List(8) { HslBand(100f,-100f,100f) },SplitTone(360f,100f,360f,100f,-100f)),requiredLutName="필요 LUT")
        val value=SharedRecipe("가".repeat(80),full,"나".repeat(160))
        val code=RecipeShare.encode(value)
        assertEquals(value,RecipeShare.decode(code));assertTrue(RecipeQr.encode(code).isNotEmpty())
    }
    private fun packed(raw: String): String {
        val d=Deflater();d.setInput(raw.toByteArray());d.finish();val b=ByteArray(raw.length+256);val n=d.deflate(b);d.end()
        val bytes=b.copyOf(n);val sum=MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        return "b7c1:${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}.$sum"
    }
    @Test fun shareRejectsTamperingOversizeCompressionBombTruncationAndMalformedFieldsWithOneLine() {
        val good=RecipeShare.encode(SharedRecipe("검사",Recipe()))
        val bad=listOf(good.dropLast(1)+(if(good.last()=="0"[0]) "1" else "0"),good.replace("b7c1:","b7c2:"),
            good+".extra",good.dropLast(20),"x".repeat(24001),packed(" ".repeat(20000)),packed("{}"),
            packed("{\"x\":".repeat(100)+"0"+"}".repeat(100)),good.replaceFirst(":",":="))
        for(code in bad) { val e=assertFailsWith<IllegalArgumentException> { RecipeShare.decode(code) };assertTrue(e.message!!.length<100 && !e.message!!.contains("\n")) }
        assertFails { RecipeShare.encode(SharedRecipe("x\ny",Recipe())) }
        assertFails { RecipeShare.encode(SharedRecipe("사용자",Recipe(lutId="user-missing"))) }
    }
    private fun lab(rgb: FloatArray): DoubleArray {
        val v=rgb.map { CpuEngine.linear(it).toDouble() }
        val xyz=doubleArrayOf((.4124564*v[0]+.3575761*v[1]+.1804375*v[2])/.95047,
            .2126729*v[0]+.7151522*v[1]+.072175*v[2],(.0193339*v[0]+.119192*v[1]+.9503041*v[2])/1.08883)
        val t=xyz.map { if(it>216.0/24389) it.pow(1.0/3) else (24389.0/27*it+16)/116 }
        return doubleArrayOf(116*t[1]-16,500*(t[0]-t[1]),200*(t[1]-t[2]))
    }
    /** Invert the documented gamut matrix, then Sony-encode physically valid sRGB patches. */
    private fun cameraLog(srgb: FloatArray): FloatArray {
        val matrix=arrayOf(doubleArrayOf(1.6269474,-.5401385,-.0868089,1.0,0.0,0.0),
            doubleArrayOf(-.1785155,1.4179409,-.2394254,0.0,1.0,0.0),doubleArrayOf(-.0444361,-.19591997,1.2403561,0.0,0.0,1.0))
        for(i in 0..2) {
            val factor=matrix[i][i];for(c in 0..5) matrix[i][c]/=factor
            for(j in 0..2) if(j!=i) { val f=matrix[j][i];for(c in 0..5) matrix[j][c]-=f*matrix[i][c] }
        }
        val linear=srgb.map { CpuEngine.linear(it).toDouble() }
        return FloatArray(3) { row ->
            val v=(0..2).sumOf { matrix[row][it+3]*linear[it] }
            if(v>=.01125) ((420+log10((v+.01)/.19)*261.5)/1023).toFloat()
            else ((v*(171.2102946929-95)/.01125+95)/1023).toFloat()
        }
    }
    @Test fun bakedCubeReimportsAndP95DeltaE76IsUnderOneAgainstDirectCpu() {
        val rng=Random(1729);val reports=mutableListOf<String>()
        val film=Lut.decode(File("../assets/luts").listFiles { f->f.extension=="lut16" }!!.sorted().first().readBytes())
        val cases=listOf(Recipe(),Recipe(edits=edits()),Recipe(wb=WhiteBalance(.2f,-.1f),ev=.3f,edits=edits()),
            Recipe(inputTransform=InputTransform.SLOG3_SGAMUT3_CINE,edits=edits()),Recipe(strength=.6f,edits=edits()),Recipe(version=1,ev=.3f))
        for((index,r) in cases.withIndex()) {
            val lut=if(index==4 || index==5) film else Lut.identity()
            val writer=StringWriter();RecipeBaker.writeCube(r,lut,writer)
            val parsed=CubeParser.parse(StringReader(writer.toString()))
            val imported=Lut.decode(parsed.encode()).copy(domainMin=parsed.domainMin,domainMax=parsed.domainMax)
            assertEquals(33,imported.size)
            val errors=(0 until 2500).map {
                val physical=FloatArray(3) { rng.nextFloat() }
                val p=if(r.inputTransform==InputTransform.NONE) physical else cameraLog(physical)
                val a=lab(CpuEngine.color(p,lut,r))
                // Reapply input interpretation before the baked sRGB LUT, with all baked edits neutral.
                val b=lab(CpuEngine.color(p,imported,Recipe(inputTransform=r.inputTransform)))
                sqrt((0..2).sumOf { (a[it]-b[it]).pow(2) })
            }.sorted()
            val p95=errors[(errors.size*.95).toInt()];reports.add("case=$index p95=$p95 max=${errors.last()}")
            File("build/reports/m5a-bake.txt").writeText(reports.joinToString("\n",postfix="\n"))
            assertTrue(p95<1,"case=$index p95=$p95")
        }
        val colorOnly=Recipe(edits=edits())
        assertContentEquals(RecipeBaker.bake(colorOnly,Lut.identity()).values,
            RecipeBaker.bake(colorOnly.copy(inputTransform=InputTransform.SLOG3_SGAMUT3_CINE),Lut.identity()).values)
        val base=Recipe(edits=edits());val spatial=base.copy(grain=Grain(7f,1f,892),vignette=1f)
        assertContentEquals(RecipeBaker.bake(base,Lut.identity()).values,RecipeBaker.bake(spatial,Lut.identity()).values)
        File("build/reports/m5a-bake.txt").writeText(reports.joinToString("\n",postfix="\n"))
    }
    private fun qrVector(version: Int,text: String) {
        val expected=javaClass.getResourceAsStream("/m5a/qr-v$version-l-mask0.txt")!!.bufferedReader().readLines()
        val actual=RecipeQr.encode(text,version)
        assertEquals(expected.size,actual.size)
        for(y in actual.indices) assertEquals(expected[y],actual[y].joinToString("") { if(it) "1" else "0" },"row $y v$version")
    }
    @Test fun qrVersion1MatchesEveryStandardModule() = qrVector(1,"b7c1:test")
    @Test fun qrVersion20MatchesEveryStandardModule() = qrVector(20,"b7c1:"+"aB9_-".repeat(150))
    @Test fun qrVersion40MatchesEveryStandardModule() = qrVector(40,"b7c1:"+"z5_-Q".repeat(500))
    @Test fun qrCapacityBoundariesAndAsciiRestriction() {
        assertEquals(21,RecipeQr.encode("a".repeat(17)).size)
        assertEquals(97,RecipeQr.encode("a".repeat(858)).size)
        assertEquals(177,RecipeQr.encode("a".repeat(2953)).size)
        assertFails { RecipeQr.encode("a".repeat(2954)) };assertFails { RecipeQr.encode("한글") }
    }
    @Test fun undoRedoBranchingAndHistoryBoundAreDeterministic() {
        val h=RecipeHistory();val a=Recipe(ev=1f);val b=Recipe(ev=2f)
        h.set(a);h.set(b);assertEquals(a,h.undo());assertEquals(b,h.redo());h.undo();h.set(Recipe(ev=3f));assertFalse(h.canRedo)
        repeat(200) { h.set(Recipe(ev=(it%10)/10f)) }
        var n=0;while(h.canUndo) {h.undo();n++};assertEquals(100,n)
    }
}
