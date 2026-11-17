package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.ui.resolvePhotoSelection
import org.junit.Test
import kotlin.test.*

class PhotoSelectionPolicyTest {
    @Test fun noSelectionDisablesActionsEvenWithPhotosInLibrary() {
        val summary = resolvePhotoSelection(emptyList(), setOf("a", "b"), setOf("a"))
        assertFalse(summary.enabled)
        assertTrue(summary.keys.isEmpty())
        assertEquals(0, summary.hiddenCount)
    }

    @Test fun deletedPhotosCannotBeAppliedOrShared() {
        val summary = resolvePhotoSelection(listOf("gone", "keep"), setOf("keep"), setOf("keep"))
        assertEquals(listOf("keep"), summary.keys)
        assertEquals(0, summary.hiddenCount)
        assertTrue(summary.enabled)
        assertFalse(resolvePhotoSelection(listOf("gone"), emptySet(), emptySet()).enabled)
    }

    @Test fun changingFilterRetainsHiddenSelectionAndItsActions() {
        val selected = listOf("jpeg", "raw")
        val available = selected.toSet()
        val rawOnly = resolvePhotoSelection(selected, available, setOf("raw"))
        assertEquals(selected, rawOnly.keys)
        assertEquals(1, rawOnly.hiddenCount)
        assertTrue(rawOnly.enabled)
        val noMatches = resolvePhotoSelection(selected, available, emptySet())
        assertEquals(selected, noMatches.keys)
        assertEquals(2, noMatches.hiddenCount)
        assertTrue(noMatches.enabled)
        assertEquals(0, resolvePhotoSelection(selected, available, available).hiddenCount)
    }

    @Test fun duplicateSelectionsAreProcessedOnceInOriginalOrder() {
        val summary = resolvePhotoSelection(listOf("b", "a", "b", "missing", "a", "c"),
            setOf("a", "b", "c"), setOf("a"))
        assertEquals(listOf("b", "a", "c"), summary.keys)
        assertEquals(2, summary.hiddenCount)
    }
}
