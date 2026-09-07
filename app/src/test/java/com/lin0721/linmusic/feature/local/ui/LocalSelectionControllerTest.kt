package com.lin0721.linmusic.feature.local.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSelectionControllerTest {
    private fun controller() = LocalSelectionController().apply { setItems(listOf("a", "b", "c", "d", "e")) }

    @Test fun dragSelectsRangeAndShrinkingRestoresPreviousSelection() {
        val selection = controller()
        selection.toggle("e")
        selection.beginDrag("b")
        selection.dragTo("d")
        assertEquals(setOf("b", "c", "d", "e"), selection.state.value.selectedIds)
        selection.dragTo("a")
        assertEquals(setOf("a", "b", "e"), selection.state.value.selectedIds)
        selection.endDrag()
        selection.dragTo("c")
        assertEquals(setOf("a", "b", "e"), selection.state.value.selectedIds)
    }

    @Test fun draggingSelectedAnchorDeselectsAndReversingRestoresRange() {
        val selection = controller()
        selection.toggleAll()
        selection.beginDrag("c")
        selection.dragTo("a")
        assertEquals(setOf("d", "e"), selection.state.value.selectedIds)
        selection.dragTo("d")
        assertEquals(setOf("a", "b", "e"), selection.state.value.selectedIds)
    }

    @Test fun selectAllUsesEntireFilteredListAndTogglesOff() {
        val selection = controller()
        selection.setItems(listOf("b", "d"))
        selection.toggleAll()
        assertEquals(setOf("b", "d"), selection.state.value.selectedIds)
        selection.toggleAll()
        assertTrue(selection.state.value.active)
        assertTrue(selection.state.value.selectedIds.isEmpty())
    }

    @Test fun orderOrFilterChangesClearSelectionAndInvalidateActiveDrag() {
        val selection = controller()
        selection.beginDrag("b")
        selection.setItems(listOf("d", "b"))
        selection.dragTo("d")
        assertFalse(selection.state.value.active)
        assertTrue(selection.state.value.selectedIds.isEmpty())
    }

    @Test fun unknownIdsCannotBeSelectedAndExplicitExitClears() {
        val selection = controller()
        selection.beginDrag("missing")
        selection.toggle("missing")
        assertFalse(selection.state.value.active)
        selection.beginDrag("a")
        selection.clear()
        assertFalse(selection.state.value.active)
        assertTrue(selection.state.value.selectedIds.isEmpty())
    }
}
