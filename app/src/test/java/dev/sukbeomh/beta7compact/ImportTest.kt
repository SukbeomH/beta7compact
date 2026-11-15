package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ImportTest {
    private val jpeg = CameraObject(1, 2, "SAMPLE.JPG", 4, 1000, 4)
    private val raw = jpeg.copy(handle = 2, name = "SAMPLE.ARW", size = 8)
    @Test fun planDeduplicatesAndGroupsRawJpegWithoutCombiningDifferentCardsOrFolders() {
        val list = ImportPlan.create(listOf(raw, jpeg, jpeg.copy(handle = 9), jpeg.copy(name = "video.MP4")), emptySet())
        assertEquals(2, list.size); assertEquals(jpeg.pair, raw.pair)
        assertNotEquals(jpeg.pair, jpeg.copy(storage = 9).pair)
        assertNotEquals(jpeg.pair, jpeg.copy(parent = 9).pair)
        assertEquals(1, ImportPlan.create(listOf(raw, jpeg), setOf(jpeg.key)).size)
    }
    @Test fun selectionAndNewOnlyAreIntersectedAndIdentitySurvivesHandleReassignment() {
        assertEquals(listOf(raw), ImportPlan.create(listOf(jpeg, raw), emptySet(), setOf(raw.key)))
        assertTrue(ImportPlan.create(listOf(jpeg), setOf(jpeg.key), setOf(jpeg.key)).isEmpty())
        assertEquals(jpeg.key, jpeg.copy(handle = 98, storage = 7).key)
        assertNotEquals(jpeg.key, jpeg.copy(size = 5).key)
        assertNotEquals(jpeg.key, jpeg.copy(captured = 2000).key)
    }
    @Test fun storagePolicyAcceptsSupportedOriginalsAndRejectsTraversalAndUnknownData() {
        assertEquals("DCIM/beta7compact/", CameraObject.PATH)
        assertEquals("image/jpeg", jpeg.mime); assertEquals("image/x-sony-arw", raw.mime)
        assertEquals("image/heif", jpeg.copy(name = "SAMPLE.HIF").mime)
        for (name in listOf("../x.JPG", "a/b.JPG", "a\\b.JPG", "a\n.JPG", "foo.MP4", "")) assertFalse(jpeg.copy(name = name).valid)
        for (size in listOf(0L, -1L, 0xffffffffL)) assertFalse(jpeg.copy(size = size).valid)
    }
    @Test fun captureTimeParsesTheCameraClockAndUnknownIsStable() {
        assertTrue(CameraObject.ptpTime("20200102T030405") > 0)
        assertEquals(0, CameraObject.ptpTime("")); assertEquals(0, CameraObject.ptpTime("garbage"))
    }
    private class Journal(val items: List<CameraObject>) : ImportJournal {
        val done = mutableSetOf<String>(); val uris = mutableMapOf<String, String>()
        var failCommit = false
        override suspend fun pending() = items.filter { it.key !in done }
        override suspend fun uri(key: String) = uris[key]
        override suspend fun staged(key: String, uri: String) { uris[key] = uri }
        override suspend fun completed(key: String, uri: String) {
            if (failCommit) { failCommit = false; error("simulated process death after publication") }
            uris[key] = uri; done.add(key)
        }
    }
    private class Store : OriginalStore {
        val bytes = mutableMapOf<String, ByteArray>(); val visible = mutableSetOf<String>()
        var count = 0; var failWrite = false
        override fun begin(item: CameraObject) = "content://synthetic/${++count}"
        override fun recover(uri: String, size: Long) = uri in visible && bytes[uri]?.size?.toLong() == size
        override fun write(uri: String, source: File) {
            if (failWrite) { failWrite = false; bytes[uri] = byteArrayOf(1); error("interrupted") }
            bytes[uri] = source.readBytes()
        }
        override fun publish(uri: String, size: Long) { check(bytes[uri]?.size?.toLong() == size); visible.add(uri) }
        override fun discard(uri: String) { check(uri !in visible); bytes.remove(uri) }
    }
    private class FakeCamera(var objects: List<CameraObject>) : Camera {
        val downloads = mutableListOf<Int>(); var short = false
        override fun list() = objects
        override fun thumbnail(item: CameraObject) = byteArrayOf(1, 2)
        override fun download(item: CameraObject, destination: File) {
            downloads.add(item.handle); destination.writeBytes(ByteArray(item.size.toInt() - if(short) 1 else 0) { (it + item.handle).toByte() })
        }
        override fun close() {}
    }
    private fun temp() = Files.createTempDirectory("beta7-test").toFile().resolve("original.part")
    @Test fun fakeCameraRunsProductionPipelineByteForByteAndSecondRunDoesNothing() = runBlocking {
        val queue = Journal(listOf(jpeg, raw)); val store = Store(); val camera = FakeCamera(listOf(jpeg, raw)); val file = temp()
        try {
            val progress = mutableListOf<Pair<Int, Int>>()
            val pipeline = ImportPipeline(queue, store, file)
            pipeline.run(camera) { done, total -> progress.add(done to total) }
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            assertEquals(2, store.visible.size); assertEquals(2, queue.done.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), store.bytes[queue.uris[jpeg.key]])
            pipeline.run(camera); assertEquals(listOf(1, 2), camera.downloads)
            assertFalse(file.exists())
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun shortOriginalNeverEntersMediaStoreAndCanBeRetried() = runBlocking {
        val queue = Journal(listOf(jpeg)); val store = Store(); val camera = FakeCamera(listOf(jpeg)).apply { short = true }; val file = temp()
        try {
            assertFails { ImportPipeline(queue, store, file).run(camera) }
            assertEquals(0, store.count); assertTrue(queue.done.isEmpty()); assertFalse(file.exists())
            camera.short = false; ImportPipeline(queue, store, file).run(camera)
            assertEquals(setOf(jpeg.key), queue.done)
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun interruptedPendingCopyIsHiddenAndRetryReplacesIt() = runBlocking {
        val queue = Journal(listOf(jpeg)); val store = Store().apply { failWrite = true }; val camera = FakeCamera(listOf(jpeg)); val file = temp()
        try {
            assertFails { ImportPipeline(queue, store, file).run(camera) }
            assertTrue(store.visible.isEmpty()); assertTrue(queue.done.isEmpty())
            val interrupted = queue.uris[jpeg.key]!!
            ImportPipeline(queue, store, file).run(camera)
            assertFalse(store.bytes.containsKey(interrupted)); assertEquals(1, store.visible.size)
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun deathAfterPublicationRecoversJournalWithoutAnotherDownloadOrDuplicate() = runBlocking {
        val queue = Journal(listOf(jpeg)).apply { failCommit = true }; val store = Store(); val camera = FakeCamera(listOf(jpeg)); val file = temp()
        try {
            assertFails { ImportPipeline(queue, store, file).run(camera) }
            assertEquals(1, store.visible.size)
            ImportPipeline(queue, store, file).run(camera)
            assertEquals(1, store.count); assertEquals(listOf(1), camera.downloads); assertEquals(setOf(jpeg.key), queue.done)
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun resumeReconcilesReassignedHandleAndStopsIfCardChanged() = runBlocking {
        val queue = Journal(listOf(jpeg)); val store = Store(); val camera = FakeCamera(listOf(jpeg.copy(handle = 11))); val file = temp()
        try {
            ImportPipeline(queue, store, file).run(camera)
            assertEquals(listOf(11), camera.downloads)
            val missing = Journal(listOf(raw))
            assertFails { ImportPipeline(missing, store, file).run(camera) }
            assertTrue(missing.done.isEmpty())
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun cancellationPreservesFinishedFilesAndResumesAtNextFile() = runBlocking {
        val queue = Journal(listOf(jpeg, raw)); val store = Store(); val camera = FakeCamera(listOf(jpeg, raw)); val file = temp()
        var active = true
        try {
            assertFails { ImportPipeline(queue, store, file).run(camera, { active }) { _, _ -> active = false } }
            assertEquals(setOf(jpeg.key), queue.done)
            ImportPipeline(queue, store, file).run(camera)
            assertEquals(listOf(1, 2), camera.downloads); assertEquals(2, queue.done.size)
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    @Test fun cancellationAfterDownloadPublishesNothing() = runBlocking {
        val queue = Journal(listOf(jpeg)); val store = Store(); val camera = FakeCamera(listOf(jpeg)); val file = temp()
        var checks = 0
        try {
            assertFails { ImportPipeline(queue, store, file).run(camera, { ++checks <= 1 }) }
            assertEquals(0, store.count); assertTrue(queue.done.isEmpty()); assertFalse(file.exists())
        } finally { checkNotNull(file.parentFile).deleteRecursively() }
    }
    // Ported from /tmp/beta7-m1-review-tests/ReviewChecks.kt: real plan + pipeline, synthetic bytes.
    @Test fun timezoneChangeNeverReplansOrRepublishesTheSameCameraObject() = runBlocking {
        val old = java.util.TimeZone.getDefault()
        val file = temp()
        try {
            fun obj() = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4,
                CameraObject.ptpTime("20200102T030405"), cameraTime = "20200102T030405")
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
            val first = obj(); val queue = Journal(listOf(first)); val store = Store()
            val camera = FakeCamera(listOf(first))
            ImportPipeline(queue, store, file).run(camera)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val second = obj(); camera.objects = listOf(second)
            assertNotEquals(first.captured, second.captured)
            assertEquals(first.key, second.key); assertEquals(first.pair, second.pair)
            assertTrue(ImportPlan.create(camera.objects, queue.done).isEmpty())
            ImportPipeline(queue, store, file).run(camera)
            assertEquals(1, store.visible.size); assertEquals(1, store.count)
            assertEquals(listOf(1), camera.downloads)
        } finally { java.util.TimeZone.setDefault(old); file.parentFile!!.deleteRecursively() }
    }
    @Test fun v1ParallelKeysPreventReimportAndResumeUnderADifferentTimezone() = runBlocking {
        val old = java.util.TimeZone.getDefault(); val file = temp()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
            val legacy = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4, CameraObject.ptpTime("20200102T030405"))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val current = legacy.copy(handle = 11, captured = CameraObject.ptpTime("20200102T030405"),
                cameraTime = "20200102T030405")
            assertTrue(ImportPlan.create(listOf(current), legacy.compatibleKeys).isEmpty())
            val queue = Journal(listOf(legacy)); val store = Store()
            ImportPipeline(queue, store, file).run(FakeCamera(listOf(current)))
            assertEquals(setOf(legacy.key), queue.done)
            assertEquals(1, store.visible.size)
            assertTrue(queue.uris.containsKey(legacy.key))
        } finally { java.util.TimeZone.setDefault(old); file.parentFile!!.deleteRecursively() }
    }
    @Test fun v1AmbiguousPendingIdentityStopsBeforePublication() = runBlocking {
        val file = temp()
        try {
            val legacy = jpeg.copy(captured = 1577934245000)
            val candidates = listOf("20200102T030405", "20200102T120405").mapIndexed { index, time ->
                legacy.copy(handle = index + 1, cameraTime = time)
            }
            val store = Store()
            assertFails { ImportPipeline(Journal(listOf(legacy)), store, file).run(FakeCamera(candidates)) }
            assertEquals(0, store.count)
        } finally { file.parentFile!!.deleteRecursively() }
    }
    @Test fun rawCameraTimestampIsPreservedVerbatimInIdentity() {
        val raw = jpeg.copy(cameraTime = "20200102T030405.1+0900")
        assertEquals(CameraObject.digest("${raw.name}\u0000${raw.size}\u000020200102T030405.1+0900"), raw.key)
        assertEquals(raw.key, raw.copy(captured = 0).key)
    }
    @Test fun migratedV1KeysCoverOriginalSuffixesAndDstGapWithoutDependingOnCurrentTimezone() {
        val old = java.util.TimeZone.getDefault()
        try {
            for (date in listOf("20200102T030405.1+0900", "20200308T023000", "20200308T023000Z")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
                val legacy = jpeg.copy(captured = CameraObject.ptpTime(date))
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                val current = jpeg.copy(captured = CameraObject.ptpTime(date), cameraTime = date)
                assertTrue(ImportPlan.create(listOf(current), legacy.compatibleKeys).isEmpty())
                assertTrue(current.matchesQueued(legacy))
            }
        } finally { java.util.TimeZone.setDefault(old) }
    }
    @Test fun newRecordsWithDifferentCameraClocksNeverMatchThroughLegacyAliases() {
        val first = jpeg.copy(cameraTime = "20200102T030405")
        val second = jpeg.copy(cameraTime = "20200102T120405")
        assertEquals(listOf(second), ImportPlan.create(listOf(first, second), first.compatibleKeys))
        assertFalse(second.matchesQueued(first))
    }
    @Test fun optionalMetadataFallbackRetainsTimezoneAndRawModeCompatibility() {
        val old = java.util.TimeZone.getDefault()
        try {
            for (date in listOf("20200102T030405", "20200102T030405.1Z")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                val raw = jpeg.copy(captured = CameraObject.ptpTime(date), cameraTime = date)
                val fallback = raw.copy(cameraTime = null)
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
                val current = fallback.copy(captured = CameraObject.ptpTime(date))
                assertTrue(current.isKnown(fallback.compatibleKeys))
                assertTrue(current.isKnown(raw.compatibleKeys))
                assertTrue(raw.isKnown(current.compatibleKeys))
                assertTrue(current.matchesQueued(fallback))
                assertTrue(current.matchesQueued(raw))
                assertTrue(raw.matchesQueued(current))
            }
        } finally { java.util.TimeZone.setDefault(old) }
    }
    @Test fun connectionStateOnlyEntersSessionAfterDiscoveryAndOpen() {
        var state = LinkState.DISCONNECTED
        assertFails { transition(state, LinkEvent.READY) }
        for (event in listOf(LinkEvent.FOUND, LinkEvent.OPEN, LinkEvent.READY)) state = transition(state, event)
        assertEquals(LinkState.SESSION, state)
        assertEquals(LinkState.SESSION, transition(state, LinkEvent.FOUND))
        assertEquals(LinkState.FAILED, transition(state, LinkEvent.ERROR))
        assertEquals(LinkState.DISCONNECTED, transition(state, LinkEvent.LOST))
        assertEquals(LinkState.DISCOVERED, transition(LinkState.FAILED, LinkEvent.FOUND))
    }
}
