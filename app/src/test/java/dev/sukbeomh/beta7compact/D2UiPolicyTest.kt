package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.ui.*
import dev.sukbeomh.beta7compact.ui.theme.BetaColors
import kotlin.math.pow
import kotlin.test.*
import org.junit.Test

class D2UiPolicyTest {
    @Test fun firstTabDependsOnlyOnSonyUsbAtLaunch() {
        assertEquals(AppTab.PHOTOS, initialTab(false))
        assertEquals(AppTab.CAMERA, initialTab(true))
    }
    @Test fun widthThresholdsUseCurrentWindow() {
        assertEquals(WindowClass.COMPACT, windowClass(599.99f))
        assertEquals(WindowClass.MEDIUM, windowClass(600f))
        assertEquals(WindowClass.MEDIUM, windowClass(839.99f))
        assertEquals(WindowClass.EXPANDED, windowClass(840f))
        assertEquals(WindowClass.COMPACT, windowClass(412f))
    }
    @Test fun portraitAndSquareUnfoldedStackWhileLandscapeSplits() {
        assertTrue(isPortraitWindow(884f, 1104f))
        assertTrue(isPortraitWindow(884f, 884f))
        assertFalse(isPortraitWindow(1104f, 884f))
        assertEquals(PaneLayout.STACKED, paneLayout(884f, 1104f))
        assertEquals(PaneLayout.STACKED, paneLayout(840f, 840f))
        assertEquals(PaneLayout.SIDE_BY_SIDE, paneLayout(1104f, 884f))
        assertEquals(PaneLayout.SIDE_BY_SIDE, paneLayout(600f, 599f))
        assertEquals(PaneLayout.STACKED, paneLayout(599f, 412f))
    }
    @Test fun tabletopNeedsKnownHalfOpenAngleAndHorizontalHingeApproximation() {
        assertTrue(isTabletop(90f, true, 1104f))
        assertTrue(isTabletop(30f, true, 600f))
        assertTrue(isTabletop(150f, true, 884f))
        for (angle in listOf(null, 0f, 29.9f, 150.1f, 180f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFalse(isTabletop(angle, true, 884f))
        assertFalse(isTabletop(90f, false, 884f))
        assertFalse(isTabletop(90f, true, 412f))
        assertEquals(PaneLayout.TABLETOP, paneLayout(1104f, 884f, true))
        assertEquals(PaneLayout.TABLETOP, paneLayout(884f, 1104f, true))
    }
    private fun box(expectedWidth: Float, expectedHeight: Float, actual: PhotoBox) {
        assertEquals(expectedWidth, actual.width, .001f)
        assertEquals(expectedHeight, actual.height, .001f)
        if (actual.height > 0) assertEquals(1.5f, actual.width / actual.height, .0001f)
    }
    @Test fun wireframeAUsesFull412Width() {
        box(412f, 274.66666f, editorPhotoBox(412f, 915f, PaneLayout.STACKED))
    }
    @Test fun wireframeBAndEUseFull884Width() {
        box(884f, 589.33333f, editorPhotoBox(884f, 1104f, paneLayout(884f, 1104f)))
        box(884f, 589.33333f, photoBox(884f))
    }
    @Test fun wireframeCLandscapeReservesPanelAndKeepsPhotoRatio() {
        box(720f, 480f, editorPhotoBox(1104f, 884f, PaneLayout.SIDE_BY_SIDE))
        // Short landscape window constrains height instead of cropping or stretching.
        box(216f, 144f, editorPhotoBox(1104f, 200f, PaneLayout.SIDE_BY_SIDE))
    }
    @Test fun wireframeDUsesHalfHeightMinusSixteenAndCentersInUpperHalf() {
        val actual = editorPhotoBox(1104f, 884f, PaneLayout.TABLETOP)
        box(639f, 426f, actual)
        assertEquals(8f, (884f / 2 - actual.height) / 2)
        box(804f, 536f, editorPhotoBox(884f, 1104f, PaneLayout.TABLETOP))
    }
    @Test fun fittedFourThirdsPortraitAndThreeHalvesNeverCrop() {
        val target = photoBox(884f)
        val fourThirds = fittedPhoto(target, 4000f, 3000f)
        assertEquals(785.7778f, fourThirds.width, .01f)
        assertEquals(target.height, fourThirds.height, .001f)
        val portrait = fittedPhoto(target, 3000f, 4000f)
        assertEquals(442f, portrait.width, .01f)
        assertEquals(target.height, portrait.height, .001f)
        val threeHalves = fittedPhoto(target, 6000f, 4000f)
        assertEquals(target.width, threeHalves.width, .001f)
        assertEquals(target.height, threeHalves.height, .001f)
        val panorama = fittedPhoto(target, 6000f, 2000f)
        assertEquals(target.width, panorama.width, .001f)
        assertEquals(294.66666f, panorama.height, .01f)
    }
    @Test fun degenerateBoxIsBoundedAndInvalidInputRejected() {
        assertEquals(PhotoBox(0f, 0f), photoBox(0f))
        assertEquals(PhotoBox(0f, 0f), photoBox(884f, 0f))
        assertFailsWith<IllegalArgumentException> { photoBox(-1f) }
        assertFailsWith<IllegalArgumentException> { photoBox(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { photoBox(412f, -1f) }
        assertFailsWith<IllegalArgumentException> { fittedPhoto(photoBox(412f), 0f, 100f) }
    }
    @Test fun completionNeverNavigatesUntilViewIsChosen() {
        val original = NavigationState(AppTab.SETTINGS)
        assertEquals(original, navigate(original, NavigationAction.ImportFinished))
        assertEquals(NavigationState(AppTab.PHOTOS), navigate(original, NavigationAction.ViewImported))
    }
    @Test fun editorAndDetailsBackAlwaysReturnToPhotosAndTabsClearDestinations() {
        val original = NavigationState(AppTab.PHOTOS)
        val detail = navigate(original, NavigationAction.View("photo-a"))
        assertEquals("photo-a", detail.detailKey)
        val editor = navigate(detail, NavigationAction.Edit(listOf("a", "b", "a")))
        assertEquals(listOf("a", "b"), editor.editorKeys)
        assertNull(editor.detailKey)
        assertEquals(original, navigate(editor, NavigationAction.Back))
        assertEquals(original, navigate(detail, NavigationAction.Back))
        assertEquals(NavigationState(AppTab.RECIPES), navigate(editor, NavigationAction.Tab(AppTab.RECIPES)))
        assertEquals(original, navigate(original, NavigationAction.Edit(emptyList())))
    }
    @Test fun pickerBackPreservesCameraDestination() {
        val picker = navigate(NavigationState(AppTab.CAMERA), NavigationAction.Pick)
        assertTrue(picker.picking)
        assertEquals(NavigationState(AppTab.CAMERA), navigate(picker, NavigationAction.Back))
    }
    @Test fun normalTextAndActionsMeetContrastTargets() {
        fun luminance(c: androidx.compose.ui.graphics.Color): Double {
            fun linear(x: Float): Double = if (x <= .04045f) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
        }
        for (surface in listOf(BetaColors.surface, BetaColors.surfaceContainer, BetaColors.surfaceContainerHigh)) {
            assertTrue(contrast(BetaColors.onSurface, surface) >= 4.5)
            assertTrue(contrast(BetaColors.onSurfaceVariant, surface) >= 4.5)
            assertTrue(contrast(BetaColors.primary, surface) >= 3.0)
        }
        assertTrue(contrast(BetaColors.primary, BetaColors.onPrimary) >= 4.5)
        assertTrue(contrast(BetaColors.errorContainer, BetaColors.onErrorContainer) >= 4.5)
    }
}
