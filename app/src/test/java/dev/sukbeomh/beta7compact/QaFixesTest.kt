package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.*
import dev.sukbeomh.beta7compact.ui.CurveTouch
import dev.sukbeomh.beta7compact.ui.resultLabel
import java.io.File
import kotlin.test.*
import org.junit.Test

class QaFixesTest {
    private fun segment(marker: Int, payload: Int, declared: Int = payload + 2) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), (declared shr 8).toByte(), declared.toByte()) + ByteArray(payload) { 0x20 }
    private fun jpeg(vararg segments: ByteArray): File = File.createTempFile("qafix", ".jpg").apply {
        deleteOnExit()
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0)) { all, s -> all + s } +
            segment(0xDA, 10) + byteArrayOf(1, 2, 3, 0xFF.toByte(), 0xD9.toByte()))
    }

    @Test fun jpegWithSegmentsThatFitIsWellFormed() {
        assertTrue(JpegFile.isWellFormed(jpeg(segment(0xE1, 60_000), segment(0xDB, 65), segment(0xC0, 15))))
    }

    @Test fun exifSegmentPastTheLimitIsDetected() {
        // What the old export wrote: 115 837 bytes of EXIF with the length wrapped to 16 bits.
        val wrapped = 115_837 and 0xFFFF
        assertEquals(50_301, wrapped)
        assertFalse(JpegFile.isWellFormed(jpeg(segment(0xE1, 115_835, wrapped), segment(0xDB, 65))))
    }

    @Test fun truncatedOrForeignFilesAreNotWellFormed() {
        assertFalse(JpegFile.isWellFormed(File("/nonexistent/file.jpg")))
        val empty = File.createTempFile("qafix", ".jpg").apply { deleteOnExit() }
        assertFalse(JpegFile.isWellFormed(empty))
        empty.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE1, 100).copyOf(50))
        assertFalse(JpegFile.isWellFormed(empty))
        empty.writeBytes("not a jpeg at all".toByteArray())
        assertFalse(JpegFile.isWellFormed(empty))
    }

    @Test fun oneDragIsOneUndoStep() {
        val start = Recipe(); val history = RecipeHistory(start)
        history.set(Recipe(ev = .1f))
        for (i in 2..40) history.set(Recipe(ev = i / 10f), continuing = true)
        assertEquals(Recipe(ev = 4f), history.current)
        assertEquals(start, history.undo())
        assertFalse(history.canUndo)
        assertEquals(Recipe(ev = 4f), history.redo())
    }

    @Test fun separateTouchesStaySeparateUndoSteps() {
        val history = RecipeHistory(Recipe())
        history.set(Recipe(ev = 1f)); history.set(Recipe(ev = 2f))
        assertEquals(Recipe(ev = 1f), history.undo())
        // A continuing change with nothing before it still records a step instead of overwriting the start.
        val fresh = RecipeHistory(Recipe()); fresh.set(Recipe(ev = 1f), continuing = true)
        assertEquals(Recipe(), fresh.undo())
    }

    @Test fun sliderPositionPicksAnExistingPointOrAnEmptySpot() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .6f), CurvePoint(1f, 1f))
        assertEquals(1, CurveTouch.pointAt(points, .51f))
        assertEquals(0, CurveTouch.pointAt(points, 0f))
        assertEquals(2, CurveTouch.pointAt(points, .99f))
        assertNull(CurveTouch.pointAt(points, .3f))
        assertEquals(.5f, CurveTouch.snap(points, .515f))
        assertEquals(.3f, CurveTouch.snap(points, .3f))
    }

    @Test fun slidingTheGraphMovesThePointThereOrAddsOne() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .6f), CurvePoint(1f, 1f))
        val (moved, movedIndex) = assertNotNull(CurveTouch.place(points, .51f, .2f))
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .2f), CurvePoint(1f, 1f)), moved)
        assertEquals(1, movedIndex)
        val (added, addedIndex) = assertNotNull(CurveTouch.place(points, .25f, 2f))
        assertEquals(CurvePoint(.25f, 1f), added[addedIndex])
        assertEquals(4, added.size)
        ToneCurve(added)
        // End points keep their position and only change height.
        assertEquals(CurvePoint(0f, .3f), assertNotNull(CurveTouch.place(points, .01f, .3f)).first[0])
    }

    @Test fun fullCurveAddsNoMorePoints() {
        val full = (0..15).map { CurvePoint(it / 15f, it / 15f) }
        assertNull(CurveTouch.place(full, .5f, .5f))
        assertNotNull(CurveTouch.place(full, 1 / 15f, .9f))
    }

    @Test fun savedResultShowsTheLutNameNotItsId() {
        val names = mapOf("print3513" to "3513 인화")
        assertEquals("3513 인화", resultLabel(Recipe(lutId = "print3513").toJson(), names))
        assertEquals("내 LUT", resultLabel(Recipe(lutId = "user-1", requiredLutName = "내 LUT").toJson(), names))
        assertEquals("레시피", resultLabel(Recipe(lutId = "gone").toJson(), names))
        assertEquals("레시피", resultLabel("broken", names))
    }
}
