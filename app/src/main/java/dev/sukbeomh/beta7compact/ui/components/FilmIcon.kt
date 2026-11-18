package dev.sukbeomh.beta7compact.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.floor

/** Label and colourway of one film stock. Colours are ARGB; text uses [FilmIcons.GLYPHS] only. */
data class FilmSpec(val name: String, val iso: String, val body: Long, val band: Long, val mark: Long)

/** One filled square of pixel text on the icon grid. */
data class PixelCell(val x: Int, val y: Int, val size: Int)

/** Pixel-art film canisters for LUTs that emulate a real film stock. Original artwork, no maker logos. */
object FilmIcons {
    private const val Y = 0xFFF4C430
    private const val G = 0xFF2E9E5B
    private const val W = 0xFFF2F2F2
    private const val K = 0xFF1C1C1C
    private const val CAN = 0xFF3A3A3A

    /** Keyed by built-in LUT id from assets/luts/manifest.json; creative and user LUTs have no entry. */
    val specs: Map<String, FilmSpec> = mapOf(
        "portra160" to FilmSpec("PORTRA", "160", Y, 0xFFB9A3E3, 0xFF3B2A63),
        "portra400" to FilmSpec("PORTRA", "400", Y, 0xFF7E5BC2, W),
        "portra800" to FilmSpec("PORTRA", "800", Y, 0xFF4B2E83, W),
        "ektar100" to FilmSpec("EKTAR", "100", Y, 0xFFC62828, W),
        "gold200" to FilmSpec("GOLD", "200", Y, 0xFFF28C28, 0xFF5A2E00),
        "pro400h" to FilmSpec("PRO-H", "400", G, 0xFFC79BD6, 0xFF3B2A63),
        "superia400" to FilmSpec("SUPERIA", "400", G, 0xFF1B3F8F, W),
        "vista100" to FilmSpec("VISTA", "100", 0xFFE8452C, 0xFF1D4E9E, W),
        "velvia50" to FilmSpec("VELVIA", "50", G, 0xFFB0206A, W),
        "provia100" to FilmSpec("PROVIA", "100", G, 0xFF4FA3E0, K),
        "astia100" to FilmSpec("ASTIA", "100", G, 0xFFE8C9A0, K),
        "sensia100" to FilmSpec("SENSIA", "100", G, 0xFF8E7CC3, W),
        "kodachrome64" to FilmSpec("KCHROME", "64", 0xFFE53935, Y, K),
        "ektachrome100" to FilmSpec("ECHROME", "100", Y, 0xFF1E63B5, W),
        "print2383" to FilmSpec("2383", "250", CAN, Y, K),
        "print2393" to FilmSpec("2393", "250", CAN, 0xFFF28C28, K),
        "print3513" to FilmSpec("3513", "250", CAN, G, W),
        "trix400" to FilmSpec("TRI-X", "400", Y, 0xFF222222, W),
        "hp5" to FilmSpec("HP5", "400", W, 0xFF2FA84F, K),
        "delta100" to FilmSpec("DELTA", "100", W, 0xFF3FB4C8, K),
        "acros100" to FilmSpec("ACROS", "100", K, G, W),
        "apx100" to FilmSpec("APX", "100", 0xFFE8452C, W, K),
    )

    /** 3 x 5 bitmaps, row by row from the top. */
    val GLYPHS: Map<Char, String> = mapOf(
        'A' to "010101111101101", 'C' to "011100100100011", 'D' to "110101101101110",
        'E' to "111100110100111", 'G' to "011100101101011", 'H' to "101101111101101",
        'I' to "111010010010111", 'K' to "101101110101101", 'L' to "100100100100111",
        'M' to "101111111101101", 'N' to "110101101101101", 'O' to "010101101101010",
        'P' to "110101110100100", 'R' to "110101110101101", 'S' to "011100010001110",
        'T' to "111010010010010", 'U' to "101101101101111", 'V' to "101101101101010",
        'X' to "101101010101101", '-' to "000000111000000",
        '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111",
        '3' to "111001111001111", '4' to "101101111001001", '5' to "111100111001111",
        '6' to "111100111101111", '8' to "111101111101111", '9' to "111101111001111",
    )

    /** Text centred on the 28-cell label of the 32-cell film icon. Fails on a character without a glyph. */
    fun textCells(text: String, top: Int, scale: Int): List<PixelCell> {
        val width = (text.length * 4 - 1) * scale
        val left = 1 + Math.floorDiv(28 - width, 2)
        return text.flatMapIndexed { i, char ->
            val bits = GLYPHS[char] ?: error("No pixel glyph for '$char'")
            bits.indices.filter { bits[it] == '1' }.map { bit ->
                PixelCell(left + (i * 4 + bit % 3) * scale, top + (bit / 3) * scale, scale)
            }
        }
    }
}

/** Film canister for [lutId]; a monochrome canister when the LUT has no film stock. Decorative only. */
@Composable fun FilmIcon(lutId: String?, modifier: Modifier = Modifier) {
    val spec = FilmIcons.specs[lutId]
    Canvas(modifier) {
        if (spec == null) drawMonoCanister() else drawFilmCanister(spec)
    }
}

/** Keep small badges within their canvas; larger icons retain whole-pixel cells. */
internal fun filmIconCellSize(width: Float, height: Float, cells: Int): Float {
    val available = minOf(width, height) / cells
    return if (available < 1f) available else floor(available)
}
private class PixelGrid(private val scope: DrawScope, cells: Int) {
    private val cell = filmIconCellSize(scope.size.width, scope.size.height, cells)
    private val left = floor((scope.size.width - cell * cells) / 2f)
    private val top = floor((scope.size.height - cell * cells) / 2f)
    fun rect(x: Int, y: Int, w: Int, h: Int, color: Long, alpha: Float = 1f) =
        scope.drawRect(Color(color), Offset(left + x * cell, top + y * cell), Size(w * cell, h * cell), alpha)
    fun cells(cells: List<PixelCell>, color: Long) {
        val path = Path()
        cells.forEach { c -> path.addRect(Rect(Offset(left + c.x * cell, top + c.y * cell), Size(c.size * cell, c.size * cell))) }
        scope.drawPath(path, Color(color))
    }
}

private fun DrawScope.drawFilmCanister(spec: FilmSpec) {
    val grid = PixelGrid(this, 32)
    grid.rect(11, 1, 8, 2, 0xFF5A5A5A)
    grid.rect(1, 3, 28, 3, 0xFFB8B8B8)
    grid.rect(1, 6, 28, 22, spec.body)
    grid.rect(1, 8, 28, 18, spec.band)
    grid.rect(1, 28, 28, 3, 0xFFB8B8B8)
    grid.rect(29, 9, 3, 16, 0xFF4A3526)
    for (y in intArrayOf(11, 16, 21)) grid.rect(30, y, 1, 2, 0xFFD9C7B0)
    grid.cells(FilmIcons.textCells(spec.name, 9, 1), spec.mark)
    grid.cells(FilmIcons.textCells(spec.iso, 15, 2), spec.mark)
}

private fun DrawScope.drawMonoCanister() {
    val grid = PixelGrid(this, 16)
    grid.rect(6, 1, 4, 1, 0xFF6A6A6A)
    grid.rect(3, 2, 10, 2, 0xFFC8C8C8)
    grid.rect(3, 4, 10, 9, 0xFF9A9A9A)
    grid.rect(3, 6, 10, 4, 0xFFE8E8E8)
    grid.rect(6, 7, 5, 2, 0xFF6A6A6A)
    grid.rect(3, 13, 10, 2, 0xFFC8C8C8)
    grid.rect(4, 4, 1, 9, 0xFFFFFFFF, 0.3f)
    grid.rect(13, 5, 3, 7, 0xFF6A6A6A)
    for (y in intArrayOf(6, 8, 10)) grid.rect(14, y, 1, 1, 0xFFE8E8E8)
}
