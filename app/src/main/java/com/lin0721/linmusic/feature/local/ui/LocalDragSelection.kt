package com.lin0721.linmusic.feature.local.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val EDGE_THRESHOLD_PX = 96f
private const val EDGE_SCROLL_STEP_PX = 22f
private const val EDGE_SCROLL_INTERVAL_MS = 16L

/**
 * Adds long-press-and-drag selection to the [LazyListState] owned by a LazyColumn.
 *
 * The modifier observes the list's stable String keys instead of making assumptions about row
 * height or the backing track list. A short tap and an ordinary scroll are left to child and
 * parent gesture handlers. Once the long press is recognized, movement is consumed and selection
 * callbacks receive only the row ids currently under the pointer.
 */
fun Modifier.localDragSelection(
    listState: LazyListState,
    enabled: Boolean,
    onStart: (String) -> Unit,
    onDragOver: (String) -> Unit,
    onEnd: () -> Unit
): Modifier = composed {
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDragOver by rememberUpdatedState(onDragOver)
    val currentOnEnd by rememberUpdatedState(onEnd)

    if (!enabled) {
        this
    } else {
        pointerInput(enabled, listState) {
            coroutineScope {
                val gestureScope = this
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val startId = listState.itemIdAt(longPress.position, nearest = false)
                        ?: return@awaitEachGesture

                    // A long press belongs to this selection gesture. Consuming it prevents a
                    // row's click handler from interpreting the eventual up event as a tap.
                    longPress.consume()
                    var latestPosition = longPress.position
                    var lastDragId = startId
                    var selecting = true
                    var edgeScrollJob: Job? = null

                    try {
                        currentOnStart(startId)
                        edgeScrollJob = gestureScope.launch {
                            while (isActive && selecting) {
                                val position = latestPosition
                                val layoutInfo = listState.layoutInfo
                                val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                                val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                                val contentStart = (layoutInfo.viewportStartOffset + layoutInfo.beforeContentPadding)
                                    .toFloat()
                                val contentEnd = (layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding)
                                    .toFloat()
                                val edgeStart = contentStart.coerceAtLeast(viewportStart)
                                val edgeEnd = contentEnd.coerceAtMost(viewportEnd)
                                val scrollDelta = when {
                                    position.y < edgeStart + EDGE_THRESHOLD_PX -> {
                                        -edgeScrollAmount(edgeStart + EDGE_THRESHOLD_PX - position.y)
                                    }

                                    position.y > edgeEnd - EDGE_THRESHOLD_PX -> {
                                        edgeScrollAmount(position.y - (edgeEnd - EDGE_THRESHOLD_PX))
                                    }

                                    else -> 0f
                                }
                                if (scrollDelta != 0f) {
                                    listState.scrollBy(scrollDelta)
                                    listState.itemIdAt(position, nearest = true)?.let { id ->
                                        if (id != lastDragId) {
                                            lastDragId = id
                                            currentOnDragOver(id)
                                        }
                                    }
                                }
                                delay(EDGE_SCROLL_INTERVAL_MS)
                            }
                        }

                        while (true) {
                            // Initial pass runs before child clickable handlers. Once the long
                            // press has started, consume every subsequent change, including the
                            // up event, so a long press without movement cannot become a click.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            latestPosition = change.position
                            listState.itemIdAt(change.position, nearest = true)?.let { id ->
                                if (id != lastDragId) {
                                    lastDragId = id
                                    currentOnDragOver(id)
                                }
                            }
                            change.consume()
                        }
                    } finally {
                        selecting = false
                        edgeScrollJob?.cancel()
                        currentOnEnd()
                    }
                }
            }
        }
    }
}

private fun edgeScrollAmount(distanceIntoEdge: Float): Float =
    EDGE_SCROLL_STEP_PX * (distanceIntoEdge / EDGE_THRESHOLD_PX).coerceIn(0.35f, 1f)

private fun LazyListState.itemIdAt(position: Offset, nearest: Boolean): String? {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val exact = visibleItems.firstOrNull { item ->
        position.y >= item.offset && position.y < item.offset + item.size
    }
    if (exact != null) return exact.key as? String
    if (!nearest || visibleItems.isEmpty()) return null

    val nearestItem = visibleItems.minByOrNull { item ->
        when {
            position.y < item.offset -> item.offset - position.y
            position.y >= item.offset + item.size -> position.y - (item.offset + item.size)
            else -> 0f
        }
    }
    return nearestItem?.key as? String
}
