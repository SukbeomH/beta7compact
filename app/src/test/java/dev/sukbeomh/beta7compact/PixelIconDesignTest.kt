package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.ColorEdits
import dev.sukbeomh.beta7compact.recipe.Grain
import dev.sukbeomh.beta7compact.recipe.InputTransform
import dev.sukbeomh.beta7compact.recipe.Recipe
import dev.sukbeomh.beta7compact.recipe.WhiteBalance
import dev.sukbeomh.beta7compact.ui.components.FilmIcons
import dev.sukbeomh.beta7compact.ui.isAdjusted
import java.io.File
import kotlin.test.*
import org.junit.Test

class PixelIconDesignTest {
    /** (id, category) pairs of the built-in LUTs; org.json is an Android stub in unit tests. */
    private fun manifest(): List<Pair<String, String>> {
        val text = File("../assets/luts/manifest.json").readText()
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        val categories = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, categories.size)
        assertTrue(ids.isNotEmpty())
        return ids.zip(categories)
    }

    @Test fun everyFilmLutHasAnIconAndCreativeLutsHaveNone() {
        val luts = manifest()
        val film = luts.filter { it.second != "creative" }.map { it.first }.toSet()
        assertEquals(film, FilmIcons.specs.keys)
        luts.filter { it.second == "creative" }.forEach { assertNull(FilmIcons.specs[it.first], it.first) }
    }

    @Test fun labelsUseOnlyKnownGlyphsAndFitTheBand() {
        FilmIcons.GLYPHS.values.forEach { bits -> assertEquals(15, bits.length); assertTrue(bits.all { it == '0' || it == '1' }) }
        FilmIcons.specs.forEach { (id, spec) ->
            assertTrue(spec.name.isNotEmpty() && spec.iso.isNotEmpty(), id)
            // The label band spans cells x 1..28 and y 8..25 of the 32-cell icon.
            val cells = FilmIcons.textCells(spec.name, 9, 1) + FilmIcons.textCells(spec.iso, 15, 2)
            assertTrue(cells.all { it.x >= 1 && it.x + it.size <= 29 && it.y >= 8 && it.y + it.size <= 26 }, id)
        }
    }

    @Test fun pixelTextIsCentredAndRejectsUnknownCharacters() {
        // "1" is the column 010 over five rows except row 2 (110) and the base (111).
        val one = FilmIcons.textCells("1", 0, 1)
        assertEquals(8, one.size)
        assertEquals(13, one.minOf { it.x }) // 3-cell glyph centred on the 28-cell label that starts at x = 1
        assertEquals(setOf(0, 1, 2, 3, 4), one.map { it.y }.toSet())
        val doubled = FilmIcons.textCells("1", 15, 2)
        assertTrue(doubled.all { it.size == 2 && (it.y - 15) % 2 == 0 })
        assertFailsWith<IllegalStateException> { FilmIcons.textCells("7", 0, 1) }
    }

    @Test fun adjustedBadgeIgnoresLutChoiceFormatVersionAndInvisibleGrain() {
        assertFalse(isAdjusted(Recipe()))
        assertFalse(isAdjusted(Recipe(lutId = "portra400")))
        assertFalse(isAdjusted(Recipe(lutId = "portra400", inputTransform = InputTransform.SLOG3_SGAMUT3_CINE)))
        assertFalse(isAdjusted(Recipe(lutId = "user-1", requiredLutName = "My LUT")))
        assertFalse(isAdjusted(Recipe(lutId = "portra400", version = 1)))
        assertFalse(isAdjusted(Recipe(grain = Grain(seed = 9))))
        assertFalse(isAdjusted(Recipe(grain = Grain(size = 8f, amount = 0f, seed = 3))))
    }

    @Test fun adjustedBadgeMarksAnyVisibleChange() {
        assertTrue(isAdjusted(Recipe(lutId = "portra400", strength = 0.8f)))
        assertTrue(isAdjusted(Recipe(ev = 0.3f)))
        assertTrue(isAdjusted(Recipe(wb = WhiteBalance(temperature = 0.15f))))
        assertTrue(isAdjusted(Recipe(vignette = 0.2f)))
        assertTrue(isAdjusted(Recipe(grain = Grain(amount = 0.1f))))
        assertTrue(isAdjusted(Recipe(edits = ColorEdits(contrast = 10f))))
        assertTrue(isAdjusted(Recipe(lutId = "portra400", strength = 0.8f, version = 1)))
    }
}
