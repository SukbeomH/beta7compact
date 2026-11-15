package android.os

class ParcelFileDescriptor(val statSize: Long) : java.io.Closeable {
    lateinit var file: java.io.File
    override fun close() {}
    companion object {
        const val MODE_CREATE = 1; const val MODE_TRUNCATE = 2; const val MODE_READ_WRITE = 4
        @JvmStatic fun open(file: java.io.File, mode: Int) = ParcelFileDescriptor(file.length()).also { it.file = file }
    }
}
class CancellationSignal
object Build { object VERSION { @JvmField var SDK_INT = 30 } }
class Bundle {
    val data = mutableMapOf<String, Any?>()
    fun putInt(key: String, value: Int) { data[key] = value }
    fun putString(key: String, value: String?) { data[key] = value }
    fun putStringArray(key: String, value: Array<String>?) { data[key] = value }
}
