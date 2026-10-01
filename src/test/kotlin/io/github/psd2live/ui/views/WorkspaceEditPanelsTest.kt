package io.github.psd2live.ui.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceEditPanelsTest {
    @Test fun focusingAnotherCanvasKeepsUnfinishedPlacementAccessible() {
        assertEquals("edit", workspacePlacementOwner("preview", listOf("edit")))
    }

    @Test fun focusedCanvasTakesPriorityWhenSeveralPlacementsArePending() {
        assertEquals("second", workspacePlacementOwner("second", listOf("first", "second")))
        assertEquals("first", workspacePlacementOwner("preview", listOf("first", "second")))
    }

    @Test fun noPendingPlacementShowsNoPanel() {
        assertNull(workspacePlacementOwner("edit", emptyList()))
    }
}
