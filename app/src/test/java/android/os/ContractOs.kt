package android.os

class ParcelFileDescriptor(val statSize: Long) : java.io.Closeable { override fun close() {} }
class CancellationSignal
object Build { object VERSION { @JvmField var SDK_INT = 30 } }
class Bundle {
    val data = mutableMapOf<String, Any?>()
    fun putInt(key: String, value: Int) { data[key] = value }
    fun putString(key: String, value: String?) { data[key] = value }
    fun putStringArray(key: String, value: Array<String>?) { data[key] = value }
}
