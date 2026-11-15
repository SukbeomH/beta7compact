package dev.sukbeomh.beta7compact.recipe

/** All strengths normalized to 0..1; WB controls -1..1; grain size in source pixels. */
data class WhiteBalance(val temperature: Float = 0f, val tint: Float = 0f)
data class Grain(val size: Float = 1f, val amount: Float = 0f, val seed: Int = 1)
enum class InputTransform { NONE, SLOG3_SGAMUT3_CINE }
data class Recipe(val lutId: String = "identity", val strength: Float = 1f,
    val inputTransform: InputTransform = InputTransform.NONE, val wb: WhiteBalance = WhiteBalance(),
    val ev: Float = 0f, val grain: Grain = Grain(), val vignette: Float = 0f, val version: Int = 2,
    val edits: ColorEdits = ColorEdits(), val requiredLutName: String = "") {
    init {
        require(requiredLutName.length<=160 && requiredLutName.none { it.isISOControl() }) { "LUT 이름이 잘못되었습니다" }
        require((version==2 || edits==ColorEdits()) && version in 1..2 && Regex("[a-zA-Z0-9_-]{1,100}").matches(lutId)) { "지원하지 않는 레시피입니다" }
        require(strength in 0f..1f && wb.temperature in -1f..1f && wb.tint in -1f..1f && ev in -5f..5f &&
            grain.size in 1f..32f && grain.amount in 0f..1f && vignette in 0f..1f) { "레시피 값이 범위를 벗어났습니다" }
    }
    fun toJson(): String {
        fun curve(c: ToneCurve) = c.points.mapIndexed { i,p -> "\"p$i\":{\"x\":${p.x},\"y\":${p.y}}" }.joinToString(",","{","}")
        val curves=edits.curves.list().mapIndexed { i,c -> "\"c$i\":${curve(c)}" }.joinToString(",","{","}")
        val bands=edits.hsl.mapIndexed { i,h -> "\"b$i\":{\"h\":${h.hue},\"s\":${h.saturation},\"l\":${h.luminance}}" }.joinToString(",","{","}")
        val t=edits.split
        val editJson="""{"highlights":${edits.highlights},"shadows":${edits.shadows},"contrast":${edits.contrast},"saturation":${edits.saturation},"vibrance":${edits.vibrance},"curves":$curves,"hsl":$bands,"split":{"sh":${t.shadowHue},"ss":${t.shadowSaturation},"hh":${t.highlightHue},"hs":${t.highlightSaturation},"balance":${t.balance}}}"""
        val metadata=if(requiredLutName.isEmpty()) "" else ",\"lutName64\":\"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(requiredLutName.toByteArray(Charsets.UTF_8))}\""
        val extra=(if(version==2) ",\"edits\":$editJson" else "")+metadata
        return """{"lutId":"$lutId","strength":$strength,"inputTransform":"${inputTransform.name}","wb":{"temperature":${wb.temperature},"tint":${wb.tint}},"ev":$ev,"grain":{"size":${grain.size},"amount":${grain.amount},"seed":${grain.seed}},"vignette":$vignette,"version":$version$extra}"""
    }
    companion object {
        fun fromJson(text: String): Recipe {
            return fromFields(RecipeJson(text).read())
        }
        internal fun fromFields(root: Map<String,Any>): Recipe {
            fun Map<String,Any>.number(k: String) = (get(k) as? Double)?.toFloat() ?: error("레시피 숫자가 없습니다")
            fun Map<String,Any>.string(k: String) = get(k) as? String ?: error("레시피 문자열이 없습니다")
            @Suppress("UNCHECKED_CAST") fun obj(k: String) = root[k] as? Map<String,Any> ?: error("레시피 항목이 없습니다")
            val w = obj("wb"); val g = obj("grain")
            val seed = g["seed"] as? Double ?: error("시드가 없습니다")
            require(seed % 1.0 == 0.0 && seed in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
            require(root["version"] == 1.0 || root["version"] == 2.0) { "지원하지 않는 레시피 버전입니다" }
            val version=(root["version"] as Double).toInt()
            @Suppress("UNCHECKED_CAST") fun Map<String,Any>.objectAt(k: String) = get(k) as? Map<String,Any> ?: error("레시피 항목이 없습니다")
            val edits=if(version==1) ColorEdits() else {
                val e=obj("edits");val curves=e.objectAt("curves");val bands=e.objectAt("hsl");val t=e.objectAt("split")
                require(curves.size==4 && bands.size==8)
                val c=(0..3).map { index ->
                    val points=curves.objectAt("c$index");require(points.size in 2..16)
                    ToneCurve((0 until points.size).map { i -> points.objectAt("p$i").let { CurvePoint(it.number("x"),it.number("y")) } })
                }
                ColorEdits(e.number("highlights"),e.number("shadows"),e.number("contrast"),e.number("saturation"),e.number("vibrance"),
                    Curves(c[0],c[1],c[2],c[3]),(0..7).map { i -> bands.objectAt("b$i").let { HslBand(it.number("h"),it.number("s"),it.number("l")) } },
                    SplitTone(t.number("sh"),t.number("ss"),t.number("hh"),t.number("hs"),t.number("balance")))
            }
            return Recipe(root.string("lutId"), root.number("strength"), InputTransform.valueOf(root.string("inputTransform")),
                WhiteBalance(w.number("temperature"),w.number("tint")), root.number("ev"),
                Grain(g.number("size"),g.number("amount"),seed.toInt()),root.number("vignette"),version,edits,(root["lutName64"] as? String)?.let {
                    String(java.util.Base64.getUrlDecoder().decode(it),Charsets.UTF_8)
                } ?: "")
        }
    }
}
/** Small strict JSON object reader; no Android dependency in the reference engine. */
const val MAX_JSON_CHARS = 16384

internal class RecipeJson(private val s: String) {
    private var i = 0
    init { require(s.length <= MAX_JSON_CHARS) }
    private fun ws() { while (i < s.length && s[i] in " \n\r\t") i++ }
    private fun take(c: Char) { ws(); require(i < s.length && s[i++] == c) { "레시피 JSON 형식 오류" } }
    private fun string(): String {
        take('"'); val out = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            if (c == '"') return out.toString()
            require(c >= ' ' && c != '\\') { "지원하지 않는 JSON 문자열입니다" }; out.append(c)
        }; error("JSON 문자열이 끝나지 않았습니다")
    }
    private fun obj(depth: Int): Map<String, Any> {
        require(depth < 7); take('{'); val m = linkedMapOf<String,Any>(); ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            val k = string(); take(':'); ws(); require(i < s.length)
            val value: Any = when (s[i]) {
                '{' -> obj(depth+1)
                '"' -> string()
                else -> {
                    val start = i
                    while (i < s.length && s[i] in "0123456789eE+.-") i++
                    val token = s.substring(start,i)
                    require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token))
                    token.toDouble().also { require(it.isFinite()) }
                }
            }
            require(m.put(k,value) == null) { "중복 JSON 항목입니다" }; ws(); require(i < s.length)
            if (s[i] == '}') { i++; return m }; take(',')
        }
    }
    fun read(): Map<String,Any> = obj(0).also { ws(); require(i == s.length) }
}
