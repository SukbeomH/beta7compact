package dev.sukbeomh.beta7compact.ui

/** Valid selection is independent of the current filter; filtering never discards a photo. */
data class PhotoSelectionSummary(val keys: List<String>, val hiddenCount: Int) {
    val enabled: Boolean get() = keys.isNotEmpty()
}

fun resolvePhotoSelection(
    selected: List<String>,
    available: Set<String>,
    visible: Set<String>,
): PhotoSelectionSummary {
    val keys = selected.distinct().filter { it in available }
    return PhotoSelectionSummary(keys, keys.count { it !in visible })
}
