package com.lin0721.linmusic.feature.local.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalSelectionState(
    val active: Boolean = false,
    val selectedIds: Set<String> = emptySet()
)

/** Selection is tied to the displayed order. Dragging back restores the pre-drag selection. */
class LocalSelectionController {
    private val mutableState = MutableStateFlow(LocalSelectionState())
    val state = mutableState.asStateFlow()
    private var ids = emptyList<String>()
    private var anchorIndex: Int? = null
    private var initialSelection = emptySet<String>()
    private var selecting = true

    fun setItems(newIds: List<String>) {
        if (ids == newIds) return
        ids = newIds.toList()
        clear()
    }

    fun beginDrag(id: String) {
        val index = ids.indexOf(id)
        if (index < 0) return
        anchorIndex = index
        initialSelection = mutableState.value.selectedIds
        selecting = id !in initialSelection
        dragTo(id)
    }

    fun dragTo(id: String) {
        val anchor = anchorIndex ?: return
        val end = ids.indexOf(id)
        if (end < 0) return
        val range = ids.subList(minOf(anchor, end), maxOf(anchor, end) + 1).toSet()
        mutableState.value = LocalSelectionState(
            active = true,
            selectedIds = if (selecting) initialSelection + range else initialSelection - range
        )
    }

    fun endDrag() {
        anchorIndex = null
        initialSelection = emptySet()
    }

    fun toggle(id: String) {
        if (id !in ids) return
        endDrag()
        val current = mutableState.value.selectedIds
        mutableState.value = LocalSelectionState(true, if (id in current) current - id else current + id)
    }

    fun toggleAll() {
        if (ids.isEmpty()) return
        endDrag()
        val all = ids.toSet()
        mutableState.value = LocalSelectionState(true, if (mutableState.value.selectedIds.containsAll(all)) emptySet() else all)
    }

    fun clear() {
        endDrag()
        mutableState.value = LocalSelectionState()
    }
}
