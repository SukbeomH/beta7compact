package dev.sukbeomh.beta7compact.recipe

/** All strengths normalized to 0..1; WB controls -1..1; grain size in source pixels. */
data class WhiteBalance(val temperature: Float = 0f, val tint: Float = 0f)
data class Grain(val size: Float = 1f, val amount: Float = 0f, val seed: Int = 1)
enum class InputTransform { NONE, SLOG3_SGAMUT3_CINE }
data class Recipe(val lutId: String = "identity", val strength: Float = 1f,
    val inputTransform: InputTransform = InputTransform.NONE, val wb: WhiteBalance = WhiteBalance(),
    val ev: Float = 0f, val grain: Grain = Grain(), val vignette: Float = 0f, val version: Int = 1) {
    init {
        require(version == 1 && Regex("[a-zA-Z0-9_-]{1,100}").matches(lutId)) { "지원하지 않는 레시피입니다" }
        require(strength in 0f..1f && wb.temperature in -1f..1f && wb.tint in -1f..1f && ev in -5f..5f &&
            grain.size in 1f..32f && grain.amount in 0f..1f && vignette in 0f..1f) { "레시피 값이 범위를 벗어났습니다" }
    }
    fun toJson() = """{"lutId":"$lutId","strength":$strength,"inputTransform":"${inputTransform.name}","wb":{"temperature":${wb.temperature},"tint":${wb.tint}},"ev":$ev,"grain":{"size":${grain.size},"amount":${grain.amount},"seed":${grain.seed}},"vignette":$vignette,"version":$version}"""
    companion object {
        fun fromJson(text: String): Recipe {
            val root = RecipeJson(text).read()
            fun Map<String,Any>.number(k: String) = (get(k) as? Double)?.toFloat() ?: error("레시피 숫자가 없습니다")
            fun Map<String,Any>.string(k: String) = get(k) as? String ?: error("레시피 문자열이 없습니다")
            @Suppress("UNCHECKED_CAST") fun obj(k: String) = root[k] as? Map<String,Any> ?: error("레시피 항목이 없습니다")
            val w = obj("wb"); val g = obj("grain")
            val seed = g["seed"] as? Double ?: error("시드가 없습니다")
            require(seed % 1.0 == 0.0 && seed in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
            require(root["version"] == 1.0) { "지원하지 않는 레시피 버전입니다" }
            return Recipe(root.string("lutId"), root.number("strength"), InputTransform.valueOf(root.string("inputTransform")),
                WhiteBalance(w.number("temperature"),w.number("tint")), root.number("ev"),
                Grain(g.number("size"),g.number("amount"),seed.toInt()),root.number("vignette"))
        }
    }
}
/** Small strict JSON object reader; no Android dependency in the reference engine. */
private class RecipeJson(private val s: String) {
    private var i = 0
    init { require(s.length <= 4096) }
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
        require(depth < 4); take('{'); val m = linkedMapOf<String,Any>(); ws()
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
