package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.usb.*
import org.junit.Test
import java.util.TimeZone
import kotlin.test.*

class RawListingPlanTest {
    private fun string(value: String) = byteArrayOf((value.length + 1).toByte()) + (value + "\u0000").toByteArray(Charsets.UTF_16LE)
    private fun dataset(name: String, format: Int, size: Int, parent: Int = 8, date: String = "20200102T030405"): ByteArray =
        buffer(52).putInt(7).putShort(format.toShort()).putShort(0).putInt(size).apply {
            position(38); putInt(parent); position(52)
        }.array() + string(name) + string(date) + byteArrayOf(0, 0)
    private fun objectFrom(handle: Int, bytes: ByteArray): CameraObject {
        return Ptp.objectInfo(handle, bytes).cameraObject()
    }
    @Test fun wireListingAndEquivalentMtpMetadataProduceSameImportIdentityAndPair() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val jpeg = objectFrom(1, dataset("TEST.JPG", 0x3801, 4))
            val raw = objectFrom(2, dataset("TEST.ARW", 0xb101, 8))
            val mtpMetadata = CameraObject(99, 7, "TEST.JPG", 4, 1577934245000, 8, "20200102T030405")
            assertEquals(8, jpeg.parent); assertEquals(mtpMetadata.key, jpeg.key)
            val queue = ImportPlan.create(listOf(jpeg, raw), mtpMetadata.compatibleKeys)
            assertEquals(listOf(raw), queue); assertEquals(jpeg.pair, raw.pair)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun corruptDatasetCannotProduceAnImportPlan() {
        val original = dataset("TEST.ARW", 0xb101, 8)
        for (end in listOf(0, 4, 12, 51, original.size - 1)) assertFails { Ptp.objectInfo(1, original.copyOf(end)) }
    }
    @Test fun sharedMtpAndRawMetadataPreserveCameraClockIncludingSuffixAndDstGap() {
        val old = TimeZone.getDefault()
        try {
            for (date in listOf("20200102T030405", "20200102T030405.1+0900", "20200308T023000Z", "20200308T023000")) {
                val wire = dataset("TEST.JPG", 0x3801, 4, date = date)
                val keys = mutableSetOf<String>(); val pairs = mutableSetOf<String>()
                for (id in listOf("Asia/Seoul", "UTC", "America/New_York", "Asia/Kathmandu")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(id))
                    // Both adapters use the same actual PTP metadata conversion.
                    val item = objectFrom(1, wire)
                    assertEquals(date, item.cameraTime)
                    keys.add(item.key); pairs.add(item.pair)
                }
                assertEquals(1, keys.size); assertEquals(1, pairs.size)
            }
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun unsignedSizesArePreservedAndUnsupportedObjectsExcluded() {
        val raw = objectFrom(1, dataset("LARGE.ARW", 0xb101, 0x90000000.toInt()))
        assertEquals(0x90000000L, raw.size)
        val folder = objectFrom(2, dataset("FOLDER", 0x3001, 0))
        assertEquals(listOf(raw), ImportPlan.create(listOf(folder, raw), emptySet()))
    }
}
