package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.recipe.Recipe
import dev.sukbeomh.beta7compact.recipe.RecipeHistory
import dev.sukbeomh.beta7compact.ui.*
import kotlin.test.*
import org.junit.Test

class D2EditorExitTest {
    private val editor = NavigationState(AppTab.PHOTOS, editorKeys = listOf("synthetic-photo"))
    private val photos = NavigationState(AppTab.PHOTOS)
    private val changed = Recipe(ev = 1f, lutId = "synthetic-lut", strength = .7f)
    private fun request(state: EditorExitState, action: NavigationAction = NavigationAction.Back) =
        editorExit(state, EditorExitEvent.Request(navigate(editor, action)))

    @Test fun unchangedRecipeLeavesImmediately() {
        val state = EditorExitState(recipe = Recipe.fromJson(Recipe().toJson()))
        assertFalse(state.hasChanges)
        val result = request(state)
        assertEquals(photos, result.destination)
        assertNull(result.state.pending)
        assertNull(result.archive)
    }

    @Test fun editsRequireAChoiceAndDoNotNavigateOrSave() {
        val state = editorExit(EditorExitState(), EditorExitEvent.Change(changed)).state
        val result = request(state)
        assertTrue(result.state.hasChanges)
        assertEquals(changed, result.state.recipe)
        assertEquals(photos, result.state.pending)
        assertNull(result.destination)
        assertNull(result.archive)
    }

    @Test fun continuingOrDismissingPreservesEditsAndAllowsAnotherRequest() {
        val waiting = request(EditorExitState(recipe = changed)).state
        val cancelled = editorExit(waiting, EditorExitEvent.ContinueEditing)
        assertEquals(EditorExitState(recipe = changed), cancelled.state)
        assertNull(cancelled.destination)
        assertNull(cancelled.archive)
        assertEquals(waiting, request(cancelled.state).state)
    }

    @Test fun cancelledPredictiveGesturePreservesEveryStateField() {
        for (state in listOf(EditorExitState(), EditorExitState(recipe = changed), request(EditorExitState(recipe = changed)).state)) {
            val result = editorExit(state, EditorExitEvent.GestureCancelled)
            assertEquals(state, result.state)
            assertNull(result.destination)
            assertNull(result.archive)
        }
    }

    @Test fun discardOnlyLeavesAfterAnExplicitRequest() {
        val state = EditorExitState(recipe = changed)
        assertNull(editorExit(state, EditorExitEvent.Discard).destination)
        val result = editorExit(request(state).state, EditorExitEvent.Discard)
        assertEquals(photos, result.destination)
        assertNull(result.archive)
        assertNull(result.state.pending)
    }

    @Test fun archiveWaitsForDatabaseSuccessBeforeLeaving() {
        val waiting = request(EditorExitState(recipe = changed)).state
        val start = editorExit(waiting, EditorExitEvent.Archive)
        assertEquals(changed, start.archive)
        assertTrue(start.state.archiving)
        assertNull(start.destination)
        for (event in listOf(EditorExitEvent.Discard, EditorExitEvent.ContinueEditing,
            EditorExitEvent.Archive, EditorExitEvent.Change(Recipe(ev = 2f)))) {
            val ignored = editorExit(start.state, event)
            assertEquals(start.state, ignored.state)
            assertNull(ignored.destination)
            assertNull(ignored.archive)
        }
        val done = editorExit(start.state, EditorExitEvent.Archived(changed))
        assertEquals(photos, done.destination)
        assertFalse(done.state.hasChanges)
        assertFalse(done.state.archiving)
    }

    @Test fun failedArchiveKeepsEditsAndDestinationAndCanRetryOrCancel() {
        val waiting = request(EditorExitState(recipe = changed)).state
        val start = editorExit(waiting, EditorExitEvent.Archive)
        val failure = editorExit(start.state, EditorExitEvent.ArchiveFailed)
        assertEquals(waiting, failure.state)
        assertNull(failure.destination)
        assertEquals(changed, editorExit(failure.state, EditorExitEvent.Archive).archive)
        assertEquals(changed, editorExit(failure.state, EditorExitEvent.ContinueEditing).state.recipe)
    }

    @Test fun bothCompletionViewButtonsUseTheSameGuard() {
        for (action in listOf(NavigationAction.ViewImported, NavigationAction.Tab(AppTab.PHOTOS))) {
            assertEquals(photos, request(EditorExitState(), action).destination)
            val guarded = request(EditorExitState(recipe = changed), action)
            assertNull(guarded.destination)
            assertEquals(photos, guarded.state.pending)
            assertNull(editorExit(guarded.state, EditorExitEvent.ContinueEditing).destination)
            assertEquals(photos, editorExit(guarded.state, EditorExitEvent.Discard).destination)
            val save = editorExit(guarded.state, EditorExitEvent.Archive)
            assertEquals(changed, save.archive)
            assertEquals(photos, editorExit(save.state, EditorExitEvent.Archived(changed)).destination)
        }
    }

    @Test fun allOtherDestinationChangesAreGuardedAndPreserveTheirDestination() {
        for (action in listOf(NavigationAction.Tab(AppTab.RECIPES), NavigationAction.View("other-photo"),
            NavigationAction.Edit(listOf("other-photo")), NavigationAction.Pick)) {
            val destination = navigate(editor, action)
            val guarded = request(EditorExitState(recipe = changed), action)
            assertNull(guarded.destination)
            assertEquals(destination, guarded.state.pending)
            // A second request must not replace the user's outstanding decision.
            assertEquals(guarded.state, request(guarded.state).state)
            assertEquals(destination, editorExit(guarded.state, EditorExitEvent.Discard).destination)
        }
    }

    @Test fun undoToOpeningRecipeClearsTheDiffAndRedoRestoresIt() {
        val history = RecipeHistory(Recipe())
        history.set(changed)
        var state = editorExit(EditorExitState(), EditorExitEvent.Change(history.current)).state
        assertTrue(state.hasChanges)
        state = editorExit(state, EditorExitEvent.Change(history.undo())).state
        assertEquals(photos, request(state).destination)
        state = editorExit(state, EditorExitEvent.Change(history.redo())).state
        assertNull(request(state).destination)
    }

    @Test fun aSuccessfulArchiveIsACheckpointButFurtherEditsNeedProtection() {
        val saved = editorExit(EditorExitState(recipe = changed), EditorExitEvent.Archived(changed)).state
        assertEquals(photos, request(saved).destination)
        val editedAgain = editorExit(saved, EditorExitEvent.Change(changed.copy(ev = 2f))).state
        assertTrue(editedAgain.hasChanges)
        assertNull(request(editedAgain).destination)
        // An old asynchronous save result cannot mark newer edits as saved or navigate away.
        val oldSave = editorExit(request(editedAgain).state, EditorExitEvent.Archived(changed))
        assertNull(oldSave.destination)
        assertTrue(oldSave.state.hasChanges)
        assertEquals(photos, oldSave.state.pending)
    }
}
