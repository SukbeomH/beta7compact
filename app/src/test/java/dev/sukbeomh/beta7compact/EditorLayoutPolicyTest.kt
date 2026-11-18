package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.ui.editorPreviewHeight
import org.junit.Test
import kotlin.test.*

class EditorLayoutPolicyTest {
    @Test fun shortWindowStillLeavesControlsAccessible() {
        val height = 180f
        val preview = editorPreviewHeight(412f, height)
        assertTrue(preview > 0f)
        assertTrue(height - preview >= height / 2)
        assertTrue(preview <= height)
    }

    @Test fun largerTextYieldsSpaceToControlsWithoutGrowingPreview() {
        val normal = editorPreviewHeight(412f, 500f, fontScale = 1f)
        val enlarged = editorPreviewHeight(412f, 500f, fontScale = 2f)
        assertTrue(enlarged < normal)
        assertTrue(500f - enlarged >= 300f)
    }

    @Test fun narrowTallWindowDoesNotStretchPhotoPastItsAspectRatio() {
        val preview = editorPreviewHeight(320f, 1000f)
        assertEquals(320f / 1.5f, preview, .001f)
        assertTrue(1000f - preview > 700f)
    }

    @Test fun tabletopAndTinyWindowsAlwaysFitTheMeasuredBody() {
        for (width in listOf(0f, 320f, 884f)) {
            for (height in listOf(0f, 1f, 100f, 500f, 1000f)) {
                for (fontScale in listOf(1f, 1.5f, 2f)) {
                    val preview = editorPreviewHeight(width, height, tabletop = true, fontScale = fontScale)
                    assertTrue(preview.isFinite() && preview >= 0f && preview <= height / 2f)
                    assertTrue(height - preview >= 0f)
                }
            }
        }
    }

    @Test fun invalidMeasurementsAreRejectedBeforeLayout() {
        assertFailsWith<IllegalArgumentException> { editorPreviewHeight(-1f, 500f) }
        assertFailsWith<IllegalArgumentException> { editorPreviewHeight(412f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { editorPreviewHeight(412f, Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { editorPreviewHeight(412f, 500f, fontScale = 0f) }
    }
}
