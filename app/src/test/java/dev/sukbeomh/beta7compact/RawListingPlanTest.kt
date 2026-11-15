package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.usb.*
import org.junit.Test
import java.util.TimeZone
import kotlin.test.*

class RawListingPlanTest {
    private fun string(value: String) = byteArrayOf((value.length + 1).toByte()) + (value + "\u0000").toByteArray(Charsets.UTF_16LE)
    private fun dataset(name: String, format: Int, size: Int, parent: Int = 8): ByteArray =
        buffer(52).putInt(7).putShort(format.toShort()).putShort(0).putInt(size).apply {
            position(38); putInt(parent); position(52)
        }.array() + string(name) + string("20200102T030405") + byteArrayOf(0, 0)
    private fun objectFrom(handle: Int, bytes: ByteArray): CameraObject {
        val obj = Ptp.objectInfo(handle, bytes)
        return CameraObject(handle, obj.storage, obj.name, obj.size, CameraObject.ptpTime(obj.date), obj.parent)
    }
    @Test fun wireListingAndEquivalentMtpMetadataProduceSameImportIdentityAndPair() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val jpeg = objectFrom(1, dataset("TEST.JPG", 0x3801, 4))
            val raw = objectFrom(2, dataset("TEST.ARW", 0xb101, 8))
            val mtpMetadata = CameraObject(99, 7, "TEST.JPG", 4, 1577934245000, 8)
            assertEquals(8, jpeg.parent); assertEquals(mtpMetadata.key, jpeg.key)
            val queue = ImportPlan.create(listOf(jpeg, raw), setOf(mtpMetadata.key))
            assertEquals(listOf(raw), queue); assertEquals(jpeg.pair, raw.pair)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun corruptDatasetCannotProduceAnImportPlan() {
        val original = dataset("TEST.ARW", 0xb101, 8)
        for (end in listOf(0, 4, 12, 51, original.size - 1)) assertFails { Ptp.objectInfo(1, original.copyOf(end)) }
    }
    @Test fun unsignedSizesArePreservedAndUnsupportedObjectsExcluded() {
        val raw = objectFrom(1, dataset("LARGE.ARW", 0xb101, 0x90000000.toInt()))
        assertEquals(0x90000000L, raw.size)
        val folder = objectFrom(2, dataset("FOLDER", 0x3001, 0))
        assertEquals(listOf(raw), ImportPlan.create(listOf(folder, raw), emptySet()))
    }
}
