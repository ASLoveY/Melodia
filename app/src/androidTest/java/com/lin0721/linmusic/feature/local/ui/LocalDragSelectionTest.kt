package com.lin0721.linmusic.feature.local.ui

import androidx.activity.ComponentActivity
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalDragSelectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPressDragReportsThreeRowsAndEndsOnce() {
        val started = mutableStateListOf<String>()
        val dragged = mutableStateListOf<String>()
        var ended = 0
        var taps = 0

        composeRule.setContent {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("selection-list").height(320.dp).localDragSelection(
                    listState = listState,
                    enabled = true,
                    onStart = { started += it },
                    onDragOver = { dragged += it },
                    onEnd = { ended++ }
                )
            ) {
                items((0 until 8).toList(), key = { it.toString() }) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("row-$index")
                            .clickable { taps++ }
                    ) {
                        Text("Song $index")
                    }
                }
            }
        }

        val rowHeight = composeRule.onNodeWithTag("row-0").fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithTag("row-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center.copy(y = center.y + rowHeight))
            moveTo(center.copy(y = center.y + rowHeight * 2f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("0"), started.toList())
            assertTrue(dragged.containsAll(listOf("1", "2")))
            assertEquals(1, ended)
            assertEquals(0, taps)
        }
    }

    @Test
    fun shortTapRemainsAvailableToRowAndDoesNotStartSelection() {
        val started = mutableStateListOf<String>()
        val dragged = mutableStateListOf<String>()
        var ended = 0
        var taps = 0

        composeRule.setContent {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("selection-list").height(320.dp).localDragSelection(
                    listState = listState,
                    enabled = true,
                    onStart = { started += it },
                    onDragOver = { dragged += it },
                    onEnd = { ended++ }
                )
            ) {
                items(listOf("0", "1"), key = { it }) { id ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("row-$id")
                            .clickable { taps++ }
                    ) { Text("Song $id") }
                }
            }
        }

        composeRule.onNodeWithTag("row-0").performTouchInput {
            down(center)
            advanceEventTime(40)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(1, taps)
            assertTrue(started.isEmpty())
            assertTrue(dragged.isEmpty())
            assertEquals(0, ended)
        }
    }

    @Test
    fun longPressWithoutMovementEndsSelectionWithoutRowTap() {
        val started = mutableStateListOf<String>()
        var ended = 0
        var taps = 0

        composeRule.setContent {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("selection-list").height(240.dp).localDragSelection(
                    listState = listState,
                    enabled = true,
                    onStart = { started += it },
                    onDragOver = {},
                    onEnd = { ended++ }
                )
            ) {
                item(key = "0") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("row-0")
                            .clickable { taps++ }
                    ) { Text("Song 0") }
                }
            }
        }

        composeRule.onNodeWithTag("row-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("0"), started.toList())
            assertEquals(1, ended)
            assertEquals(0, taps)
        }
    }

    @Test
    fun holdingAtBottomEdgeScrollsAndUpdatesDragRange() {
        val started = mutableStateListOf<String>()
        val dragged = mutableStateListOf<String>()
        var ended = 0

        composeRule.setContent {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("selection-list").height(240.dp).localDragSelection(
                    listState = listState,
                    enabled = true,
                    onStart = { started += it },
                    onDragOver = { dragged += it },
                    onEnd = { ended++ }
                )
            ) {
                items((0 until 40).toList(), key = { it.toString() }) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("row-$index")
                    ) { Text("Song $index") }
                }
            }
        }

        val listNode = composeRule.onNodeWithTag("selection-list")
        val listBounds = listNode.fetchSemanticsNode().boundsInRoot
        val rowBounds = composeRule.onNodeWithTag("row-0").fetchSemanticsNode().boundsInRoot
        val start = Offset(
            x = rowBounds.center.x - listBounds.left,
            y = rowBounds.center.y - listBounds.top
        )
        val bottomEdge = Offset(
            x = start.x,
            y = listBounds.height - 4f
        )

        listNode.performTouchInput {
            down(start)
            advanceEventTime(700)
            // Keep the pointer in the list's lower edge while time advances. The helper's
            // independent edge loop must keep scrolling without another move event.
            moveTo(bottomEdge)
            SystemClock.sleep(400)
            repeat(20) {
                advanceEventTime(80)
                moveTo(bottomEdge)
            }
            up()
        }

        composeRule.waitUntil(5_000) {
            dragged.any { it.toIntOrNull()?.let { index -> index >= 3 } == true }
        }
        composeRule.runOnIdle {
            assertEquals(listOf("0"), started.toList())
            assertTrue(dragged.any { it.toIntOrNull()?.let { index -> index >= 3 } == true })
            assertEquals(1, ended)
        }
    }
}
