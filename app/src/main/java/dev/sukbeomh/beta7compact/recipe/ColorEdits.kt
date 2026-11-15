package dev.sukbeomh.beta7compact.recipe

import kotlin.math.*

data class CurvePoint(val x: Float, val y: Float)
/** Shape-preserving cubic Hermite interpolation. Flat/reversing slopes get zero tangents. */
data class ToneCurve(val points: List<CurvePoint> = listOf(CurvePoint(0f,0f),CurvePoint(1f,1f))) {
    init {
        require(points.size in 2..16 && points.first().x == 0f && points.last().x == 1f &&
            points.all { it.x in 0f..1f && it.y in 0f..1f } &&
            points.zipWithNext().all { (a,b) -> b.x-a.x >= .00001f }) { "톤커브 제어점이 잘못되었습니다" }
    }
    private val slopes = points.zipWithNext { a,b -> (b.y-a.y)/(b.x-a.x) }
    private val tangents = FloatArray(points.size) { i ->
        when(i) {
            0 -> slopes.first(); points.lastIndex -> slopes.last()
            else -> {
                val a=slopes[i-1]; val b=slopes[i]
                if(a*b<=0f) 0f else {
                    val h0=points[i].x-points[i-1].x; val h1=points[i+1].x-points[i].x
                    val w0=2*h1+h0; val w1=h1+2*h0
                    (w0+w1)/(w0/a+w1/b)
                }
            }
        }
    }
    fun sample(value: Float): Float {
        val x=value.coerceIn(0f,1f)
        val i=points.indexOfLast { it.x<=x }.coerceIn(0,points.size-2)
        val a=points[i]; val b=points[i+1]; val h=b.x-a.x; val t=(x-a.x)/h
        return ((2*t*t*t-3*t*t+1)*a.y+(t*t*t-2*t*t+t)*h*tangents[i]+
            (-2*t*t*t+3*t*t)*b.y+(t*t*t-t*t)*h*tangents[i+1]).coerceIn(min(a.y,b.y),max(a.y,b.y))
    }
    // GPU consumes the same exact Hermite coefficients, not a sampled approximation.
    fun packed(): FloatArray = FloatArray(16*3) { index ->
        val i=index/3
        if(i>=points.size) 0f else when(index%3) { 0->points[i].x; 1->points[i].y; else->tangents[i] }
    }
}
data class Curves(val rgb: ToneCurve = ToneCurve(), val r: ToneCurve = ToneCurve(),
    val g: ToneCurve = ToneCurve(), val b: ToneCurve = ToneCurve()) {
    fun list() = listOf(rgb,r,g,b)
}
data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    init { require(listOf(hue,saturation,luminance).all { it in -100f..100f }) { "HSL 값이 범위를 벗어났습니다" } }
}
data class SplitTone(val shadowHue: Float = 0f, val shadowSaturation: Float = 0f,
    val highlightHue: Float = 0f, val highlightSaturation: Float = 0f, val balance: Float = 0f) {
    init { require(shadowHue in 0f..360f && highlightHue in 0f..360f && shadowSaturation in 0f..100f &&
        highlightSaturation in 0f..100f && balance in -100f..100f) { "스플릿 톤 값이 범위를 벗어났습니다" } }
}
data class ColorEdits(val highlights: Float = 0f, val shadows: Float = 0f, val contrast: Float = 0f,
    val saturation: Float = 0f, val vibrance: Float = 0f, val curves: Curves = Curves(),
    val hsl: List<HslBand> = List(8) { HslBand() }, val split: SplitTone = SplitTone()) {
    init { require(listOf(highlights,shadows,contrast,saturation,vibrance).all { it in -100f..100f } && hsl.size==8) { "색 편집 값이 범위를 벗어났습니다" } }
}
object ColorMath {
    val centers = floatArrayOf(0f,30f,60f,120f,180f,240f,270f,300f)
    fun weights(hue: Float): FloatArray {
        val h=((hue%360)+360)%360
        val i=centers.indexOfLast { it<=h }.coerceAtLeast(0); val j=(i+1)%8
        val end=if(j==0) 360f else centers[j]
        val t=(h-centers[i])/(end-centers[i]); val w=t*t*(3-2*t)
        return FloatArray(8).also { it[i]=1-w;it[j]=w }
    }
    fun hsl(rgb: FloatArray): FloatArray {
        val hi=rgb.max();val lo=rgb.min();val d=hi-lo;val l=(hi+lo)*.5f
        if(d<1e-7f) return floatArrayOf(0f,0f,l)
        val h=when(hi) { rgb[0]->(rgb[1]-rgb[2])/d; rgb[1]->(rgb[2]-rgb[0])/d+2;else->(rgb[0]-rgb[1])/d+4 }
        return floatArrayOf(((h*60)%360+360)%360,d/(1-abs(2*l-1)),l)
    }
    fun rgb(hue: Float,s: Float,l: Float): FloatArray {
        val h=((hue%360+360)%360)/60;val c=(1-abs(2*l-1))*s;val x=c*(1-abs(h%2-1));val m=l-c/2
        val v=when(h.toInt()) {0->floatArrayOf(c,x,0f);1->floatArrayOf(x,c,0f);2->floatArrayOf(0f,c,x);3->floatArrayOf(0f,x,c);4->floatArrayOf(x,0f,c);else->floatArrayOf(c,0f,x)}
        return FloatArray(3) { v[it]+m }
    }
    fun apply(input: FloatArray,e: ColorEdits): FloatArray {
        val l=input[0]*.2126f+input[1]*.7152f+input[2]*.0722f
        val shift=(e.shadows/100*(1-l).pow(2)+e.highlights/100*l*l)*.25f
        val channels=listOf(e.curves.r,e.curves.g,e.curves.b)
        val c=FloatArray(3) { channels[it].sample(e.curves.rgb.sample(((input[it]+shift-.5f)*2f.pow(e.contrast/100)+.5f).coerceIn(0f,1f))) }
        val hsl=hsl(c);val weights=weights(hsl[0])
        fun band(get: (HslBand)->Float) = e.hsl.indices.sumOf { (weights[it]*get(e.hsl[it])).toDouble() }.toFloat()/100
        val h=hsl[0]+band { it.hue }*30
        val s=(hsl[1]*(1+e.saturation/100+e.vibrance/100*(1-hsl[1]))*(1+band { it.saturation })).coerceIn(0f,1f)
        val light=(hsl[2]+band { it.luminance }*.25f).coerceIn(0f,1f)
        val out=rgb(h,s,light);val split=e.split
        val t=((light-.5f)*2-split.balance/100).coerceIn(-1f,1f)*.5f+.5f
        val shadow=rgb(split.shadowHue,1f,.5f);val highlight=rgb(split.highlightHue,1f,.5f)
        return FloatArray(3) { (out[it]+(shadow[it]-.5f)*(1-t)*split.shadowSaturation/500+
            (highlight[it]-.5f)*t*split.highlightSaturation/500).coerceIn(0f,1f) }
    }
}
