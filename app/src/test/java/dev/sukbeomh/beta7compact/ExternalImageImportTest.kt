package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.core.CameraObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.*

class ExternalImageImportTest {
    @Test fun copiedBytesAreUnchangedAndIdentityDependsOnContent() {
        val bytes=ByteArray(130000) { (it*13).toByte() };val output=ByteArrayOutputStream()
        val a=copyExternalImage(ByteArrayInputStream(bytes),output)
        assertContentEquals(bytes,output.toByteArray());assertEquals(bytes.size.toLong(),a.size)
        val b=copyExternalImage(ByteArrayInputStream(bytes),ByteArrayOutputStream())
        assertEquals(a,b);assertTrue(a.key.startsWith("external:"));assertEquals(64,a.hash.length)
        assertNotEquals(a.key,copyExternalImage(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream()).key)
    }
    @Test fun emptyOversizeStalledAndCancelledStreamsFailBeforeExtraWrite() {
        assertFails {copyExternalImage(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream())}
        val out=ByteArrayOutputStream()
        assertFails {copyExternalImage(ByteArrayInputStream(ByteArray(10)),out,9)}
        assertEquals(0,out.size())
        assertFails {copyExternalImage(object:InputStream() {
            override fun read()=0
            override fun read(b:ByteArray,off:Int,len:Int)=0
        },ByteArrayOutputStream())}
        assertFailsWith<CancellationException> {
            copyExternalImage(ByteArrayInputStream(ByteArray(100000)),ByteArrayOutputStream()) {throw CancellationException()}
        }
    }
    @Test fun exactLimitAndKnownShaAreAccepted() {
        val result=copyExternalImage(ByteArrayInputStream("abc".toByteArray()),ByteArrayOutputStream(),3)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",result.hash)
        assertEquals(3L,result.size)
    }
    @Test fun fileNameUsesDecodedFormatAndDoesNotTrustExtensionOrPaths() {
        assertEquals("photo.jpg",ExternalImagePolicy.name("photo.arw","image/jpeg"))
        assertEquals("image.png",ExternalImagePolicy.name("../image.jpeg","image/png"))
        assertEquals("image.webp",ExternalImagePolicy.name("C:\\folder\\image","image/webp"))
        assertEquals("external-photo.heic",ExternalImagePolicy.name(null,"image/heic"))
        assertFalse(ExternalImagePolicy.name("x\n\r.jpg","image/jpeg").any {it.isISOControl()})
        assertTrue(ExternalImagePolicy.name("x".repeat(200),"image/png").length<=124)
        assertFails {ExternalImagePolicy.name("image.gif","image/gif")}
    }
    @Test fun publicationOrdersWriteBeforeVisibilityAndDatabase() = runBlocking {
        val steps=mutableListOf<String>()
        val result=publishExternalImage<String>(create={steps+="create";"new-copy"},write={steps+="write:$it"},
            publish={steps+="publish"},record={steps+="record"},delete={steps+="delete"})
        assertEquals("new-copy",result)
        assertEquals(listOf("create","write:new-copy","publish","record"),steps)
    }
    @Test fun failuresInWritePublishOrRecordDeleteOnlyAllocatedCopy() = runBlocking {
        for(failure in listOf("write","publish","record")) {
            val deleted=mutableListOf<String>()
            suspend fun step(name:String) {if(name==failure) error(name)}
            assertFailsWith<IllegalStateException> {
                publishExternalImage<String>(create={"new-copy"},write={step("write")},publish={step("publish")},
                    record={step("record")},delete={deleted+=it})
            }
            assertEquals(listOf("new-copy"),deleted)
        }
    }
    @Test fun cancellationBeforeCommitCleansCopy() = runBlocking {
        var deleted=false;var recorded=false
        val job=launch {
            publishExternalImage<String>(create={"new-copy"},write={currentCoroutineContext().cancel()},publish={},
                record={recorded=true},delete={currentCoroutineContext().ensureActive();deleted=true})
        }
        job.join();assertTrue(job.isCancelled);assertTrue(deleted);assertFalse(recorded)
    }
    @Test fun cancellationDuringCommitDoesNotDeleteRecordedPhoto() = runBlocking {
        var deleted=false;var recorded=false;lateinit var job:Job
        job=launch(start=CoroutineStart.LAZY) {
            publishExternalImage<String>(create={"new-copy"},write={},publish={},record={
                job.cancel();yield();recorded=true
            },delete={deleted=true})
        }
        job.start();job.join();assertTrue(recorded);assertFalse(deleted)
    }
    private class Dao(initial:List<Photo>):PhotoDao {
        val rows=initial.associateBy {it.key}.toMutableMap()
        override fun observe()=flowOf(rows.values.toList())
        override suspend fun all()=rows.values.toList()
        override suspend fun pending()=all().filter {it.imported==null}
        override suspend fun uris()=all().mapNotNull {it.uri}
        override suspend fun get(key:String)=rows[key]
        override suspend fun insert(photos:List<Photo>) {photos.forEach {rows.putIfAbsent(it.key,it)}}
        override suspend fun hashed(key:String,hash:String) {rows[key]=rows.getValue(key).copy(contentHash=hash)}
        override suspend fun staged(key:String,uri:String) {rows[key]=rows.getValue(key).copy(uri=uri)}
        override suspend fun complete(key:String,uri:String,now:Long) {rows[key]=rows.getValue(key).copy(uri=uri,imported=now)}
    }
    @Test fun externalPhotosDoNotSuppressCameraEnqueueOrEnterCameraJournal() = runBlocking {
        val camera=Photo.from(CameraObject(1,2,"same.jpg",123,1000,3,"20200101T000000"))
        val external=camera.copy(key="external:hash",uri="content://external",imported=1)
        val dao=Dao(listOf(external))
        dao.enqueue(listOf(camera));assertNotNull(dao.get(camera.key))
        val journal=RoomJournal(dao)
        assertTrue(journal.completedItems().isEmpty())
        assertEquals(listOf(camera.key),journal.pending().map {it.key})
        dao.rows[external.key]=external.copy(imported=null)
        assertEquals(listOf(camera.key),journal.pending().map {it.key})
    }
}
