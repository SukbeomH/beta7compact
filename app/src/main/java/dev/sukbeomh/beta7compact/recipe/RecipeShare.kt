package dev.sukbeomh.beta7compact.recipe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** No LUT payload, URIs, or file paths. Checksums detect corruption, not authorship. */
data class SharedRecipe(val name: String, val recipe: Recipe, val lutName: String = "")
object RecipeShare {
    const val MAX_CODE_CHARS = 24000
    private const val MAX_PAYLOAD = 16384
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    private fun text(s: String) = encoder.encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun utf8(b: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    private fun validate(value: SharedRecipe) {
        require(value.name.isNotBlank() && value.name.length<=80 && value.name.none { it.isISOControl() } &&
            value.lutName.length<=160 && value.lutName.none { it.isISOControl() }) { "레시피 이름이 잘못되었습니다" }
        require(!value.recipe.lutId.startsWith("user-") || value.lutName.isNotBlank()) { "사용자 LUT 이름이 필요합니다" }
    }
    fun encode(value: SharedRecipe): String {
        validate(value)
        val raw="""{"name":"${text(value.name)}","lutName":"${text(value.lutName)}","recipe":${value.recipe.toJson()}}""".toByteArray(Charsets.UTF_8)
        require(raw.size<=MAX_PAYLOAD) { "공유 레시피가 너무 큽니다" }
        val deflater=Deflater(9);val out=ByteArrayOutputStream()
        try {
            deflater.setInput(raw);deflater.finish();val buffer=ByteArray(1024)
            while(!deflater.finished()) { val n=deflater.deflate(buffer);check(n>0);out.write(buffer,0,n) }
        } finally { deflater.end() }
        val bytes=out.toByteArray()
        return "b7c1:${encoder.encodeToString(bytes)}.${checksum(bytes)}".also { require(it.length<=MAX_CODE_CHARS) }
    }
    fun decode(input: String): SharedRecipe {
        try {
            require(input.length<=MAX_CODE_CHARS) { "공유 코드가 너무 큽니다" }
            val code=input.trim()
            require(code.startsWith("b7c1:")) { "공유 코드 접두사가 잘못되었습니다" }
            val pieces=code.removePrefix("b7c1:").split(".")
            require(pieces.size==2 && pieces[0].matches(Regex("[A-Za-z0-9_-]+")) && pieces[1].matches(Regex("[0-9a-f]{16}"))) { "공유 코드 형식이 잘못되었습니다" }
            val bytes=Base64.getUrlDecoder().decode(pieces[0])
            require(encoder.encodeToString(bytes)==pieces[0]) { "공유 코드 인코딩이 잘못되었습니다" }
            require(checksum(bytes)==pieces[1]) { "공유 코드 체크섬이 다릅니다" }
            val inflater=Inflater();val out=ByteArrayOutputStream()
            try {
                inflater.setInput(bytes);val buffer=ByteArray(1024)
                while(!inflater.finished()) {
                    val n=inflater.inflate(buffer,0,minOf(buffer.size,MAX_PAYLOAD-out.size()+1))
                    require(out.size()+n<=MAX_PAYLOAD) { "공유 레시피가 너무 큽니다" }
                    require(n>0 || inflater.finished()) { "공유 코드 압축이 잘못되었습니다" };out.write(buffer,0,n)
                }
                require(inflater.remaining==0) { "공유 코드에 남는 데이터가 있습니다" }
            } finally { inflater.end() }
            val root=RecipeJson(utf8(out.toByteArray())).read()
            fun field(key: String) = utf8(Base64.getUrlDecoder().decode(root[key] as? String ?: error("필드 없음")))
            require(root.keys==setOf("name","lutName","recipe"))
            @Suppress("UNCHECKED_CAST")
            val fields=root["recipe"] as? Map<String,Any> ?: error("레시피 없음")
            val recipe=Recipe.fromFields(fields)
            return SharedRecipe(field("name"),recipe,field("lutName")).also { validate(it) }
        } catch(e: Exception) {
            val safe=setOf("공유 코드가 너무 큽니다","공유 코드 접두사가 잘못되었습니다","공유 코드 형식이 잘못되었습니다",
                "공유 코드 인코딩이 잘못되었습니다","공유 코드 체크섬이 다릅니다","공유 레시피가 너무 큽니다",
                "공유 코드 압축이 잘못되었습니다","공유 코드에 남는 데이터가 있습니다","레시피 이름이 잘못되었습니다","사용자 LUT 이름이 필요합니다")
            throw IllegalArgumentException(e.message?.takeIf { it in safe } ?: "공유 레시피 필드 또는 범위가 잘못되었습니다")
        }
    }
    fun uniqueName(desired: String,existing: Collection<String>): String {
        val base=desired.trim().ifBlank { "내 레시피" }.take(80)
        if(base !in existing) return base
        var i=2
        while(true) { val suffix=" ($i)";val candidate=base.take(80-suffix.length)+suffix;if(candidate !in existing) return candidate;i++ }
    }
}

/** Bakes encoded-sRGB color transforms, per the M2 LUT contract.
 * Input interpretation remains before this LUT; grain/vignette remain after it.
 */
object RecipeBaker {
    fun bake(recipe: Recipe,lut: Lut): Lut {
        val n=33;val values=FloatArray(n*n*n*3)
        val colorRecipe=recipe.copy(inputTransform=InputTransform.NONE)
        for(b in 0 until n) for(g in 0 until n) for(r in 0 until n) {
            val color=CpuEngine.color(floatArrayOf(r/32f,g/32f,b/32f),lut,colorRecipe)
            for(c in 0..2) values[3*(r+n*g+n*n*b)+c]=color[c]
        }
        return Lut(n,values,"beta7compact recipe")
    }
    fun writeCube(recipe: Recipe,lut: Lut,writer: java.io.Writer) {
        val baked=bake(recipe,lut)
        writer.write("# Input: encoded sRGB. Apply ${recipe.inputTransform.name} before this LUT.\n")
        writer.write("TITLE \"beta7compact recipe\"\nLUT_3D_SIZE 33\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n")
        for(i in baked.values.indices step 3) writer.write("${baked.values[i]} ${baked.values[i+1]} ${baked.values[i+2]}\n")
    }
}

/** Bounded history of accepted edit states. */
class RecipeHistory(initial: Recipe = Recipe()) {
    var current=initial; private set
    private val past=java.util.ArrayDeque<Recipe>();private val future=java.util.ArrayDeque<Recipe>()
    val canUndo get()=past.isNotEmpty()
    val canRedo get()=future.isNotEmpty()
    /** [continuing] folds this change into the previous step, so one drag is undone in one go. */
    fun set(next: Recipe, continuing: Boolean=false) {
        if(next==current) return
        if(continuing && past.isNotEmpty()) { current=next;future.clear();return }
        if(past.size==100) past.removeFirst()
        past.addLast(current);current=next;future.clear()
    }
    fun undo(): Recipe { if(canUndo) { future.addLast(current);current=past.removeLast() };return current }
    fun redo(): Recipe { if(canRedo) { past.addLast(current);current=future.removeLast() };return current }
}
