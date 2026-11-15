package dev.sukbeomh.beta7compact

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.transfer.MediaOriginalStore
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Port of the review's media/ counterexample; collection queries exclude pending by default. */
class MediaOriginalStoreContractTest {
    private class Resolver : ContentResolver() {
        data class Row(val id: Long, val owner: String, val path: String,
                       var pending: Int = 1, var bytes: ByteArray = byteArrayOf())
        val rows = linkedMapOf<String, Row>()
        val deleted = mutableListOf<String>()
        var bundleQueries = 0
        var legacyQueries = 0
        var nextId = 0L
        override fun insert(uri: Uri, values: ContentValues): Uri {
            val target = Uri.parse("$uri/${++nextId}")
            rows[target.toString()] = Row(nextId, "synthetic.app", values.data["relative_path"] as String)
            return target
        }
        private fun queryRows(uri: Uri, selection: String?, args: Array<String>?, include: Boolean): Cursor {
            val specific = rows[uri.toString()]
            val found = if (specific != null) listOf(specific) else {
                assertNotNull(selection)
                assertTrue(selection.contains("is_pending = 1"))
                assertTrue(selection.contains("owner_package_name = ?"))
                assertTrue(selection.contains("relative_path = ?"))
                assertContentEquals(arrayOf("synthetic.app", CameraObject.PATH), args)
                rows.values.filter { (include || it.pending == 0) && it.pending == 1 &&
                    it.owner == args!![0] && it.path == args[1] }
            }
            return object : Cursor {
                var index = -1
                override fun moveToFirst(): Boolean { index = 0; return found.isNotEmpty() }
                override fun moveToNext() = ++index < found.size
                override fun getInt(index: Int) = found[this.index].pending
                override fun getLong(index: Int) = found[this.index].id
                override fun close() {}
            }
        }
        override fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor {
            if ("?includePending=1" in uri.toString()) legacyQueries++
            return queryRows(uri, selection, args, "?includePending=1" in uri.toString())
        }
        override fun query(uri: Uri, projection: Array<String>, args: Bundle?, signal: CancellationSignal?): Cursor {
            bundleQueries++
            assertEquals(MediaStore.MATCH_INCLUDE, args!!.data[MediaStore.QUERY_ARG_MATCH_PENDING])
            @Suppress("UNCHECKED_CAST")
            return queryRows(uri, args.data[QUERY_ARG_SQL_SELECTION] as String?,
                args.data[QUERY_ARG_SQL_SELECTION_ARGS] as Array<String>?, true)
        }
        override fun openFileDescriptor(uri: Uri, mode: String) = ParcelFileDescriptor(rows.getValue(uri.toString()).bytes.size.toLong())
        override fun openInputStream(uri: Uri) = rows.getValue(uri.toString()).bytes.inputStream()
        override fun openOutputStream(uri: Uri, mode: String) = object : ByteArrayOutputStream() {
            override fun close() { rows.getValue(uri.toString()).bytes = toByteArray() }
        }
        override fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int {
            rows.getValue(uri.toString()).pending = values.data["is_pending"] as Int
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            deleted.add(uri.toString()); rows.remove(uri.toString()); return 1
        }
    }
    @Test fun api29And30PlusCleanOnlyUnknownOwnedPendingAndPreservePublishedOriginals() {
        val old = Build.VERSION.SDK_INT
        try {
            for (api in listOf(29, 30, 35)) {
                Build.VERSION.SDK_INT = api
                val resolver = Resolver()
                val store = MediaOriginalStore(Context(resolver, "synthetic.app"))
                val item = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4, 0)
                val orphan = store.begin(item)
                val known = store.begin(item)
                val published = store.begin(item)
                resolver.rows.getValue(published).apply { pending = 0; bytes = byteArrayOf(1, 2, 3, 4) }
                val foreign = store.begin(item)
                resolver.rows[foreign] = resolver.rows.getValue(foreign).copy(owner = "other.app")
                val otherPath = store.begin(item)
                resolver.rows[otherPath] = resolver.rows.getValue(otherPath).copy(path = "DCIM/other/")
                store.cleanOrphans(setOf(known))
                assertEquals(listOf(orphan), resolver.deleted)
                assertEquals(setOf(known, published, foreign, otherPath), resolver.rows.keys)
                assertTrue(store.recover(published, 4))
                assertEquals(dev.sukbeomh.beta7compact.core.sha256(byteArrayOf(1, 2, 3, 4).inputStream()), store.contentHash(published))
                assertFailsWith<IllegalStateException> { store.discard(published) }
                assertEquals(listOf(orphan), resolver.deleted)
                assertEquals(if (api >= 30) 1 else 0, resolver.bundleQueries)
                assertEquals(if (api == 29) 1 else 0, resolver.legacyQueries)
            }
        } finally { Build.VERSION.SDK_INT = old }
    }
}
