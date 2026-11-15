package android.database

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
}
