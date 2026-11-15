package android.content

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.OutputStream

// Narrow JVM contract double: product bytecode is compiled against the real Android SDK.
class ContentValues {
    val data = mutableMapOf<String, Any?>()
    fun put(key: String, value: String?) { data[key] = value }
    fun put(key: String, value: Int?) { data[key] = value }
    fun put(key: String, value: Long?) { data[key] = value }
}
object ContentUris {
    @JvmStatic fun withAppendedId(uri: Uri, id: Long) = Uri.parse("$uri/$id")
}
open class Context(val contentResolver: ContentResolver, val packageName: String) {
    private var manager: android.hardware.usb.UsbManager? = null
    constructor(manager: android.hardware.usb.UsbManager) : this(object : ContentResolver() {
        override fun insert(uri: Uri, values: ContentValues): Uri? = error("unused")
        override fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? = error("unused")
        override fun query(uri: Uri, projection: Array<String>, args: Bundle?, signal: android.os.CancellationSignal?): Cursor? = error("unused")
        override fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? = error("unused")
        override fun openOutputStream(uri: Uri, mode: String): OutputStream? = error("unused")
        override fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int = error("unused")
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = error("unused")
    }, "synthetic") { this.manager = manager }
    open fun <T> getSystemService(type: Class<T>): T = checkNotNull(type.cast(checkNotNull(manager)))
}
abstract class ContentResolver {
    companion object {
        const val QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection"
        const val QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args"
    }
    abstract fun insert(uri: Uri, values: ContentValues): Uri?
    abstract fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor?
    abstract fun query(uri: Uri, projection: Array<String>, args: Bundle?, signal: android.os.CancellationSignal?): Cursor?
    abstract fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor?
    open fun openInputStream(uri: Uri): java.io.InputStream? = null
    abstract fun openOutputStream(uri: Uri, mode: String): OutputStream?
    abstract fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int
    abstract fun delete(uri: Uri, selection: String?, args: Array<String>?): Int
}
