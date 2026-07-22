package com.krystals.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilamentGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun interactionRecompositionDoesNotCancelContinuousOrbit() {
        val commands = mutableListOf<ViewerCommand>()
        composeRule.setContent {
            var state by remember { mutableStateOf(InteractionState()) }
            Box(
                Modifier
                    .testTag("gesture-surface")
                    .size(300.dp)
                    .filamentViewerGestures(
                        key = Unit,
                        isLocked = { state.session.locked },
                        onCommand = { command ->
                            commands += command
                            state = InteractionReducer.reduce(state, command)
                        },
                        onTap = {},
                    ),
            )
        }

        composeRule.onNodeWithTag("gesture-surface").performTouchInput {
            down(center)
            moveBy(Offset(12f, 0f))
            moveBy(Offset(12f, 4f))
            moveBy(Offset(8f, 6f))
            up()
        }

        assertTrue(commands.count { it is ViewerCommand.Orbit } >= 3)
    }

    @Test
    fun twoPointersEmitZoomAndPanWithoutOrbit() {
        val commands = mutableListOf<ViewerCommand>()
        composeRule.setContent {
            Box(
                Modifier
                    .testTag("gesture-surface")
                    .size(300.dp)
                    .filamentViewerGestures(
                        key = Unit,
                        isLocked = { false },
                        onCommand = commands::add,
                        onTap = {},
                    ),
            )
        }

        composeRule.onNodeWithTag("gesture-surface").performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveTo(0, center - Offset(45f, 0f) + Offset(12f, 10f))
            moveTo(1, center + Offset(45f, 0f) + Offset(12f, 10f))
            up(0)
            up(1)
        }

        assertTrue(commands.any { it is ViewerCommand.Zoom })
        val pans = commands.filterIsInstance<ViewerCommand.Pan>()
        assertEquals(-12f, pans.sumOf { it.dxPx.toDouble() }.toFloat(), 0.1f)
        assertEquals(-10f, pans.sumOf { it.dyPx.toDouble() }.toFloat(), 0.1f)
        assertTrue(commands.none { it is ViewerCommand.Orbit })
    }
}
