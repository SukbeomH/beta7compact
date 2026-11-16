package dev.sukbeomh.beta7compact.ui

import dev.sukbeomh.beta7compact.recipe.Recipe

/** The checkpoint is the opening recipe, or the last successfully archived recipe. */
data class EditorExitState(
    val initialRecipe: Recipe = Recipe(),
    val recipe: Recipe = initialRecipe,
    val pending: NavigationState? = null,
    val archiving: Boolean = false,
) {
    val hasChanges get() = recipe != initialRecipe
}

sealed interface EditorExitEvent {
    data class Change(val recipe: Recipe) : EditorExitEvent
    data class Request(val destination: NavigationState) : EditorExitEvent
    data object ContinueEditing : EditorExitEvent
    data object GestureCancelled : EditorExitEvent
    data object Discard : EditorExitEvent
    data object Archive : EditorExitEvent
    data class Archived(val recipe: Recipe) : EditorExitEvent
    data object ArchiveFailed : EditorExitEvent
}

data class EditorExitTransition(
    val state: EditorExitState,
    val destination: NavigationState? = null,
    val archive: Recipe? = null,
)

/** No navigation or database effects occur until the caller consumes the returned effect. */
fun editorExit(state: EditorExitState, event: EditorExitEvent): EditorExitTransition {
    fun stay(next: EditorExitState = state) = EditorExitTransition(next)
    return when (event) {
        is EditorExitEvent.Change -> stay(if (state.archiving) state else state.copy(recipe = event.recipe))
        is EditorExitEvent.Request -> when {
            state.archiving || state.pending != null -> stay()
            state.hasChanges -> stay(state.copy(pending = event.destination))
            else -> EditorExitTransition(state, destination = event.destination)
        }
        EditorExitEvent.ContinueEditing -> stay(if (state.archiving) state else state.copy(pending = null))
        EditorExitEvent.GestureCancelled -> stay()
        EditorExitEvent.Discard -> if (state.archiving) stay() else
            EditorExitTransition(state.copy(pending = null), destination = state.pending)
        EditorExitEvent.Archive -> if (state.pending == null || state.archiving) stay() else
            EditorExitTransition(state.copy(archiving = true), archive = state.recipe)
        is EditorExitEvent.Archived -> {
            val next = state.copy(initialRecipe = event.recipe, archiving = false)
            if (!next.hasChanges) EditorExitTransition(next.copy(pending = null), destination = state.pending)
            else stay(next)
        }
        EditorExitEvent.ArchiveFailed -> stay(state.copy(archiving = false))
    }
}
