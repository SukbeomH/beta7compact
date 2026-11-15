package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.test.*

class RecipeEngineTest {
    private fun cube(n: Int=2,header: String="",point: (Int,Int,Int)->String={ r,g,b -> "${r/(n-1f)} ${g/(n-1f)} ${b/(n-1f)}" }) =
        "TITLE \"Public # synthetic\"\nLUT_3D_SIZE $n\n$header\n"+(0 until n).flatMap { b -> (0 until n).flatMap { g -> (0 until n).map { r -> point(r,g,b) } } }.joinToString("\n")
    @Test fun parserAcceptsCommentsTitleEndpointsAndAllSupportedSizes() {
        for(n in listOf(2,17,33,65)) {
            val lut=CubeParser.parse(StringReader(cube(n)))
            assertEquals(n,lut.size);assertEquals("Public # synthetic",lut.title)
            for(p in listOf(floatArrayOf(0f,0f,0f),floatArrayOf(1f,1f,1f),floatArrayOf(.12f,.34f,.56f)))
                lut.sample(p).zip(p.toList()).forEach { (a,b) -> assertEquals(b,a,1e-6f) }
        }
    }
    @Test fun parserAppliesPerChannelDomainsAndPersistsAsB7lt() {
        val lut=CubeParser.parse(StringReader(cube(header="DOMAIN_MIN -1 0 0\nDOMAIN_MAX 1 2 1")))
        val loaded=Lut.decode(lut.encode()).copy(domainMin=lut.domainMin,domainMax=lut.domainMax)
        val p=loaded.sample(floatArrayOf(0f,1f,.3f))
        assertEquals(.5f,p[0],2e-5f);assertEquals(.5f,p[1],2e-5f);assertEquals(.3f,p[2],2e-5f)
    }
    @Test fun narrowDomainPreservesExactBreakpointsWithoutResampling() {
        val lut=CubeParser.parse(StringReader(cube(header="DOMAIN_MIN 0.49 0 0\nDOMAIN_MAX 0.51 1 1")))
        assertEquals(2,lut.size)
        assertEquals(0f,lut.sample(floatArrayOf(.49f,.3f,.7f))[0],1e-6f)
        assertEquals(1f,lut.sample(floatArrayOf(.51f,.3f,.7f))[0],1e-6f)
        assertEquals(.5f,lut.sample(floatArrayOf(.50f,.3f,.7f))[0],1e-6f)
    }
    @Test fun parserRejectsMalformedWithoutEchoingInput() {
        val valid=cube()
        val bad=listOf("",valid.replace("LUT_3D_SIZE 2","LUT_3D_SIZE 1"),valid.replace("LUT_3D_SIZE 2","LUT_3D_SIZE 66"),
            valid+"\n0 0 0",valid.substringBeforeLast('\n'),valid.replace("0.0 0.0 0.0","NaN 0 0"),
            valid.replace("0.0 0.0 0.0","Infinity 0 0"),valid.replace("0.0 0.0 0.0","2 0 0"),
            valid.replace("LUT_3D_SIZE 2","LUT_1D_SIZE 2"),cube(header="DOMAIN_MAX 0 1 1"),
            cube(header="DOMAIN_MIN 0 0"),cube(header="DOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0"),
            valid+"\nTITLE \"late\"",valid.replace("TITLE \"Public # synthetic\"","TITLE unquoted"),
            cube(header="DOMAIN_MIN 0 0 NaN"),valid.replace("LUT_3D_SIZE 2","LUT_3D_SIZE 2\nLUT_3D_SIZE 2"))
        bad.forEach { text -> assertFailsWith<IllegalArgumentException> { CubeParser.parse(StringReader(text)) } }
    }
    @Test fun binaryChecksEveryHeaderFieldAndExactLength() {
        val good=Lut.identity().encode()
        assertContentEquals(good,Lut.decode(good).encode())
        for(offset in listOf(0,4,6,8)) {
            val bad=good.copyOf();bad[offset]=0
            assertFailsWith<IllegalArgumentException> { Lut.decode(bad) }
        }
        val reserved=good.copyOf();reserved[12]=1;assertFailsWith<IllegalArgumentException> { Lut.decode(reserved) }
        assertFailsWith<IllegalArgumentException> { Lut.decode(good.copyOf(good.size-1)) }
        assertFailsWith<IllegalArgumentException> { Lut.decode(good+byteArrayOf(0)) }
    }
    @Test fun all27BuiltinsMatchIndependentDoubleTrilinearWithDeltaE76() {
        val files=File("../assets/luts").listFiles { f -> f.extension=="lut16" }!!.sorted()
        assertEquals(27,files.size);val rng=Random(1729)
        var maxDelta=0.0
        files.forEach { file ->
            val lut=Lut.decode(file.readBytes());assertEquals(33,lut.size)
            repeat(100) {
                val p=FloatArray(3) { rng.nextFloat() }
                val expected=reference(lut,p);val actual=lut.sample(p)
                val a=lab(expected);val b=lab(actual)
                val delta=sqrt(a.indices.sumOf { (a[it]-b[it]).pow(2) });maxDelta=maxOf(maxDelta,delta)
                assertTrue(delta<0.001,"ΔE76=$delta")
            }
        }
        File("build/reports/m2-color.txt").apply { checkNotNull(parentFile).mkdirs();writeText("27 LUTs x 100 patches, max DeltaE76=$maxDelta (float vs independent double interpolation)\n") }
    }
    private fun reference(l: Lut,p: FloatArray): FloatArray {
        val v=p.map { it.toDouble()*(l.size-1) };val lo=v.map { floor(it).toInt() };val hi=lo.map { minOf(it+1,l.size-1) }
        return FloatArray(3) { c ->
            var value=0.0
            for(b in 0..1) for(g in 0..1) for(r in 0..1) {
                val r0=if(r==0) lo[0] else hi[0];val g0=if(g==0) lo[1] else hi[1];val b0=if(b==0) lo[2] else hi[2]
                val w=(if(r==0) 1-v[0]+lo[0] else v[0]-lo[0])*(if(g==0) 1-v[1]+lo[1] else v[1]-lo[1])*(if(b==0) 1-v[2]+lo[2] else v[2]-lo[2])
                value+=l.values[3*(r0+l.size*g0+l.size*l.size*b0)+c]*w
            };value.toFloat()
        }
    }
    private fun lab(rgb: FloatArray): DoubleArray {
        val v=rgb.map { CpuEngine.linear(it).toDouble() }
        val xyz=doubleArrayOf((.4124564*v[0]+.3575761*v[1]+.1804375*v[2])/.95047,
            .2126729*v[0]+.7151522*v[1]+.072175*v[2],(.0193339*v[0]+.119192*v[1]+.9503041*v[2])/1.08883)
        val t=xyz.map { if(it>216.0/24389) it.pow(1.0/3) else (24389.0/27*it+16)/116 }
        return doubleArrayOf(116*t[1]-16,500*(t[0]-t[1]),200*(t[1]-t[2]))
    }
    @Test fun retainedPublicCubeSourcesParse() {
        val files=File("../tools/luts/sources/editorhub").listFiles { f -> f.name.endsWith(".cube.gz") }!!
        assertEquals(5,files.size)
        files.forEach { f -> GZIPInputStream(f.inputStream()).reader().use { assertEquals(33,CubeParser.parse(it).size) } }
    }
    @Test fun recipeJsonRoundTripsAllParametersAndRejectsInvalidVersionNumbers() {
        val r=Recipe("portra400",.73f,InputTransform.SLOG3_SGAMUT3_CINE,WhiteBalance(-.3f,.8f),1.2f,Grain(3.5f,.75f,Int.MIN_VALUE),.3f)
        assertEquals(r,Recipe.fromJson(r.toJson()));assertEquals(Recipe(),Recipe.fromJson(Recipe().toJson()))
        for(bad in listOf(r.toJson().replace("\"version\":1","\"version\":2"),r.toJson().replace("0.73","NaN"),
            r.toJson().replace("0.73","1.5"),r.toJson()+"x",r.toJson().replace("\"strength\":0.73","\"strength\":0.73,\"strength\":0.2"))) {
            assertFails { Recipe.fromJson(bad) }
        }
        assertFails { Recipe(ev=Float.NaN) };assertFails { Recipe(lutId="../bad") };assertFails { Recipe(grain=Grain(size=0f)) }
    }
    @Test fun sonyReferenceBlackGreyWhiteAndToe() {
        assertEquals(0f,CpuEngine.slog3(95f/1023f),1e-7f)
        assertEquals(.18f,CpuEngine.slog3(420f/1023f),1e-6f)
        val white=((420+log10(.91/.19)*261.5)/1023).toFloat()
        assertEquals(.9f,CpuEngine.slog3(white),1e-6f)
        assertEquals(.01125f,CpuEngine.slog3(171.2102946929f/1023f),1e-7f)
        val gray=CpuEngine.input(FloatArray(3) {420f/1023f})
        gray.forEach { assertEquals(CpuEngine.srgb(.18f),it,1e-5f) }
    }
    @Test fun sonyNonNeutralGamutReference() {
        fun encode(x: Double) = ((420+log10((x+.01)/.19)*261.5)/1023).toFloat()
        val out=CpuEngine.input(floatArrayOf(encode(.05),encode(.1),encode(.2)))
        val expected=floatArrayOf(.099670349f,.322695074f,.512985132f)
        out.zip(expected.toList()).forEach { (a,b) -> assertEquals(b,a,2e-6f) }
    }
    @Test fun grainIsSeededLumaDependentAndIndependentOfTileBoundaries() {
        val lut=Lut.identity();val r=Recipe(grain=Grain(3f,.7f,717),vignette=.2f)
        val p=floatArrayOf(.4f,.5f,.6f)
        val first=CpuEngine.pixel(p,lut,r,513f,22f,6000,4000)
        assertContentEquals(first,CpuEngine.pixel(p,lut,r,513f,22f,6000,4000))
        assertFalse(first.contentEquals(CpuEngine.pixel(p,lut,r.copy(grain=r.grain.copy(seed=718)),513f,22f,6000,4000)))
        assertEquals(CpuEngine.noise(4,7,1),CpuEngine.noise(4,7,1))
        val dark=CpuEngine.pixel(FloatArray(3) {.1f},lut,r.copy(vignette=0f),0f,0f,10,10)[0]-.1f
        val mid=CpuEngine.pixel(FloatArray(3) {.5f},lut,r.copy(vignette=0f),0f,0f,10,10)[0]-.5f
        assertTrue(abs(mid)>abs(dark))
    }
    @Test fun zeroStrengthExposureAndClamping() {
        val p=floatArrayOf(.2f,.4f,.8f)
        val black=Lut(2,FloatArray(24))
        val out=CpuEngine.pixel(p,black,Recipe(strength=0f),0f,0f,1,1)
        out.zip(p.toList()).forEach { (a,b) -> assertEquals(b,a,1e-6f) }
        val ev=CpuEngine.pixel(p,Lut.identity(),Recipe(ev=1f),0f,0f,1,1)
        assertEquals(CpuEngine.srgb(CpuEngine.linear(.2f)*2),ev[0],1e-6f)
        assertTrue(ev.all { it in 0f..1f })
    }
    @Test fun arwExtractsLargestJpegFromIfdChainAndRejectsInvalidOffsets() {
        val src=File.createTempFile("public-arw-",".arw");val out=File.createTempFile("public-jpeg-",".jpg")
        try {
            val b=ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x4949);b.putShort(42);b.putInt(8)
            b.putShort(2);b.putShort(513);b.putShort(4);b.putInt(1);b.putInt(100)
            b.putShort(514);b.putShort(4);b.putInt(1);b.putInt(6);b.putInt(40)
            b.position(40);b.putShort(2);b.putShort(513);b.putShort(4);b.putInt(1);b.putInt(120)
            b.putShort(514);b.putShort(4);b.putInt(1);b.putInt(8);b.putInt(0)
            b.position(100);b.put(byteArrayOf(-1,-40,1,2,-1,-39));b.position(120);val expected=byteArrayOf(-1,-40,1,2,3,4,-1,-39);b.put(expected)
            src.writeBytes(b.array());ArwPreview.extract(src,out);assertContentEquals(expected,out.readBytes())
            b.putInt(4,9999);src.writeBytes(b.array());assertFails { ArwPreview.extract(src,out) }
        } finally { src.delete();out.delete() }
    }
}
