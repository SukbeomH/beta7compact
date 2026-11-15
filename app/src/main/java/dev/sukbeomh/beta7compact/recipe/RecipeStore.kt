package dev.sukbeomh.beta7compact.recipe

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

// UI metadata is separate from immutable B7LT data.
data class LutEntry(val id: String, val name: String, val category: String, val path: String, val sha: String, val preview: String?)
class RecipeStore(private val context: Context) {
    private val directory = File(context.filesDir,"luts").apply { mkdirs() }
    fun entries(): List<LutEntry> {
        val manifest = context.assets.open("luts/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schema_version") == 1)
        val a = manifest.getJSONArray("luts")
        val builtIn = (0 until a.length()).map { i -> a.getJSONObject(i).let {
            LutEntry(it.getString("id"),it.getString("display_name"),it.getString("category"),
                it.getString("path"),it.getString("sha256"),it.getJSONObject("preview").getString("path"))
        } }
        val imported = directory.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { val o = JSONObject(f.readText()); val id = f.nameWithoutExtension
                require(Regex("user-[a-f0-9-]{36}").matches(id))
                LutEntry(id,o.getString("title"),"user","$id.lut16",o.getString("sha256"),null)
            }.getOrNull()
        }.sortedBy { it.name }
        return listOf(LutEntry("identity","원본 색상","none","","",null))+builtIn+imported
    }
    fun load(id: String): Lut {
        if (id == "identity") return Lut.identity()
        val entry = entries().firstOrNull { it.id == id } ?: error("선택한 LUT가 없습니다")
        val bytes = if (id.startsWith("user-")) File(directory,entry.path).readBytes()
            else context.assets.open("luts/${entry.path}").use { it.readBytes() }
        require(hash(bytes) == entry.sha) { "LUT 무결성 검사가 실패했습니다" }
        val lut = Lut.decode(bytes,entry.name)
        if (!id.startsWith("user-")) return lut
        val metadata = JSONObject(File(directory,"$id.json").readText())
        fun domain(key: String, fallback: FloatArray): FloatArray {
            val a = metadata.optJSONArray(key) ?: return fallback
            require(a.length() == 3) { "DOMAIN 범위가 잘못되었습니다" }
            return FloatArray(3) { a.getDouble(it).toFloat() }
        }
        return lut.copy(domainMin=domain("domainMin",floatArrayOf(0f,0f,0f)),domainMax=domain("domainMax",floatArrayOf(1f,1f,1f)))
    }
    fun importCube(uri: Uri): String {
        val lut = context.contentResolver.openInputStream(uri)?.use { CubeParser.parse(it) }
            ?: error("LUT 파일을 읽을 수 없습니다")
        val id = "user-${UUID.randomUUID()}"; val bytes = lut.encode()
        val binary = File(directory,"$id.lut16"); val metadata = File(directory,"$id.json")
        try {
            binary.outputStream().use { it.write(bytes); it.fd.sync() }
            metadata.writeText(JSONObject().put("title",lut.title).put("sha256",hash(bytes)).put("domainMin",JSONArray(lut.domainMin.toList())).put("domainMax",JSONArray(lut.domainMax.toList())).toString())
        } catch (t: Throwable) { binary.delete();metadata.delete();throw t }
        return id
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
