package dev.sukbeomh.beta7compact.ui

import dev.sukbeomh.beta7compact.recipe.Grain
import dev.sukbeomh.beta7compact.recipe.Recipe

/** Platform-free decisions; dimensions are the current app window in dp, not display size. */
enum class AppTab(val label: String) { CAMERA("카메라"), PHOTOS("사진"), RECIPES("레시피"), SETTINGS("설정") }
enum class WindowClass { COMPACT, MEDIUM, EXPANDED }
enum class PaneLayout { STACKED, SIDE_BY_SIDE, TABLETOP }
fun initialTab(sonyUsbConnected: Boolean) = if (sonyUsbConnected) AppTab.CAMERA else AppTab.PHOTOS
fun windowClass(width: Float): WindowClass = when {
    width < 600f -> WindowClass.COMPACT
    width < 840f -> WindowClass.MEDIUM
    else -> WindowClass.EXPANDED
}
fun isPortraitWindow(width: Float, height: Float) = height >= width
fun paneLayout(width: Float, height: Float, tabletop: Boolean = false): PaneLayout = when {
    tabletop -> PaneLayout.TABLETOP
    windowClass(width) == WindowClass.COMPACT || isPortraitWindow(width, height) -> PaneLayout.STACKED
    else -> PaneLayout.SIDE_BY_SIDE
}
/** Angle + rotation approximate a horizontal hinge on book-style, portrait-natural foldables.
 * Unknown angle never guesses a posture from window size alone. */
fun isTabletop(hingeAngle: Float?, quarterTurn: Boolean, width: Float): Boolean =
    width >= 600f && quarterTurn && hingeAngle != null && hingeAngle.isFinite() && hingeAngle in 30f..150f

data class NavigationState(val tab: AppTab, val editorKeys: List<String> = emptyList(), val picking: Boolean = false, val detailKey: String? = null)
sealed interface NavigationAction {
    data class Tab(val tab: AppTab) : NavigationAction
    data class Edit(val keys: List<String>) : NavigationAction
    data class View(val key: String) : NavigationAction
    data object Pick : NavigationAction
    data object Back : NavigationAction
    data object ImportFinished : NavigationAction
    data object ViewImported : NavigationAction
}
fun navigate(state: NavigationState, action: NavigationAction): NavigationState = when (action) {
    is NavigationAction.Tab -> NavigationState(action.tab)
    is NavigationAction.Edit -> if (action.keys.isEmpty()) state else NavigationState(AppTab.PHOTOS, action.keys.distinct())
    is NavigationAction.View -> NavigationState(AppTab.PHOTOS, detailKey = action.key)
    NavigationAction.Pick -> NavigationState(AppTab.CAMERA, picking = true)
    NavigationAction.Back -> when {
        state.detailKey != null -> NavigationState(AppTab.PHOTOS)
        state.editorKeys.isNotEmpty() -> NavigationState(AppTab.PHOTOS)
        state.picking -> NavigationState(AppTab.CAMERA)
        else -> state
    }
    NavigationAction.ImportFinished -> state // Completion only offers an explicit View action.
    NavigationAction.ViewImported -> NavigationState(AppTab.PHOTOS)
}

/** True when a recipe changes more than its LUT choice. The baseline is the editor's own reset, which keeps
 * the LUT and input transform; format version, grain seed and the size of invisible grain are not adjustments. */
fun isAdjusted(recipe: Recipe): Boolean {
    val grain = if (recipe.grain.amount == 0f) Grain() else recipe.grain.copy(seed = Grain().seed)
    val baseline = Recipe(lutId = recipe.lutId, inputTransform = recipe.inputTransform, requiredLutName = recipe.requiredLutName)
    return recipe.copy(grain = grain, version = 3, colorVersion = 2) != baseline.copy(version = 3)
}

/** A 3:2 display box, independent of the image aspect ratio. Units can be dp or px. */
data class PhotoBox(val width: Float, val height: Float)
fun photoBox(availableWidth: Float, maxHeight: Float = Float.POSITIVE_INFINITY): PhotoBox {
    require(availableWidth.isFinite() && availableWidth >= 0f && !maxHeight.isNaN() && maxHeight >= 0f)
    val width = minOf(availableWidth, maxHeight * 1.5f)
    return PhotoBox(width, width / 1.5f)
}
/** C reserves 352dp panel + 16dp gap + 16dp outer margin; D uses half-height minus 8dp each side. */
fun editorPhotoBox(width: Float, height: Float, layout: PaneLayout): PhotoBox = when (layout) {
    PaneLayout.STACKED -> photoBox(width)
    PaneLayout.SIDE_BY_SIDE -> photoBox((width - 384f).coerceAtLeast(0f), (height - 56f).coerceAtLeast(0f))
    PaneLayout.TABLETOP -> photoBox(width, (height / 2f - 16f).coerceAtLeast(0f))
}
/** Body space excludes the measured header/footer and the gap between preview and controls. */
fun editorPreviewHeight(bodyWidth: Float, bodyHeight: Float, tabletop: Boolean = false, fontScale: Float = 1f): Float {
    require(bodyWidth.isFinite() && bodyWidth >= 0f && bodyHeight.isFinite() && bodyHeight >= 0f)
    require(fontScale.isFinite() && fontScale > 0f)
    val controls = minOf(160f * fontScale, bodyHeight * .6f)
    val previewCap = bodyHeight * if (tabletop) .5f else .45f
    return minOf(bodyWidth / 1.5f, previewCap, (bodyHeight - controls).coerceAtLeast(0f))
}

fun fittedPhoto(box: PhotoBox, imageWidth: Float, imageHeight: Float): PhotoBox {
    require(imageWidth.isFinite() && imageHeight.isFinite() && imageWidth > 0f && imageHeight > 0f)
    val scale = minOf(box.width / imageWidth, box.height / imageHeight)
    return PhotoBox(imageWidth * scale, imageHeight * scale)
}

/** How the tone-curve slider and graph map onto control points. */
object CurveTouch {
    /** A point this close to the slider position, as a fraction of the width, is the one being edited. */
    const val NEAR = .02f
    private const val GAP = .002f

    /** Index of the control point at slider position [x], or null when that spot has no point yet. */
    fun pointAt(points: List<dev.sukbeomh.beta7compact.recipe.CurvePoint>, x: Float): Int? =
        points.indices.minByOrNull { kotlin.math.abs(points[it].x - x) }?.takeIf { kotlin.math.abs(points[it].x - x) <= NEAR }

    /** Slider position pulled onto a nearby point, so an existing point is easy to pick up again. */
    fun snap(points: List<dev.sukbeomh.beta7compact.recipe.CurvePoint>, x: Float): Float = pointAt(points, x)?.let { points[it].x } ?: x

    /** Points with one at [x] set to height [y], and its index: the existing point there, or a new one. Null when the curve is full. */
    fun place(points: List<dev.sukbeomh.beta7compact.recipe.CurvePoint>, x: Float, y: Float): Pair<List<dev.sukbeomh.beta7compact.recipe.CurvePoint>, Int>? {
        val height = y.coerceIn(0f, 1f)
        pointAt(points, x)?.let { i -> return points.toMutableList().also { it[i] = it[i].copy(y = height) } to i }
        if (points.size >= 16 || points.any { kotlin.math.abs(it.x - x) < GAP }) return null
        val placed = (points + dev.sukbeomh.beta7compact.recipe.CurvePoint(x.coerceIn(0f, 1f), height)).sortedBy { it.x }
        return placed to placed.indexOfFirst { it.x == x.coerceIn(0f, 1f) }
    }
}

/** Name shown under a saved result: the LUT's display name, never its internal id. */
fun resultLabel(recipeJson: String, lutNames: Map<String, String>): String {
    val recipe = runCatching { Recipe.fromJson(recipeJson) }.getOrNull() ?: return "레시피"
    return lutNames[recipe.lutId] ?: recipe.requiredLutName.ifBlank { "레시피" }
}
