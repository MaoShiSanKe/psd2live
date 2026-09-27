package io.github.psd2live.ui.views

import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.DEFAULT_DOCK_MODULES
import io.github.psd2live.ui.state.PRIMARY_CANVAS_ID
import io.github.psd2live.ui.state.SidebarSide
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.isCanvasModule
import io.github.psd2live.ui.state.presetEditorWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DockLayoutTest {
    @Test fun newCanvasStaysBesideTheDefaultCanvasInsideTheWorkspace() {
        val base = defaultDockLayout()
        val placed = requireNotNull(reconcileDockModules(base, listOf("canvas", "canvas:new"), emptyList()))

        assertEquals(base.id, placed.id)
        assertEquals(base.ratio, placed.ratio)
        assertEquals(base.second, placed.second)
        assertEquals(base.first?.first, placed.first?.first)
        assertTrue(placed.first?.allModules()?.containsAll(listOf("canvas", "canvas:new", "hierarchy", "log")) == true)

        assertSame(placed, reconcileDockModules(placed, listOf("canvas", "canvas:new"), emptyList()))
        val history = requireNotNull(reconcileDockModules(placed, listOf("canvas", "canvas:new"), listOf("history")))
        assertEquals(base.id, history.id)
        assertEquals(base.second, history.second)
        assertTrue(history.first?.allModules()?.contains("history") == true)
    }

    @Test fun repairsPreviouslySavedRootLevelCanvasSplits() {
        val base = defaultDockLayout()
        val malformed = dockModule(base, "canvas:first", "canvas", DockSide.RIGHT)
        assertNotEquals(base.id, malformed.id)
        val malformedTwice = dockModule(malformed, "canvas:second", "canvas", DockSide.RIGHT)

        val repaired = repairLegacyCanvasDocking(malformedTwice)
        assertEquals(base.id, repaired.id)
        assertEquals(base.second, repaired.second)
        assertTrue(repaired.first?.allModules()?.containsAll(listOf("canvas", "canvas:first", "canvas:second")) == true)
        assertEquals(malformedTwice.allModules().toSet(), repaired.allModules().toSet())

        val custom = dockBesideModule(base, "canvas:custom", "canvas", DockSide.RIGHT)
        assertSame(custom, repairLegacyCanvasDocking(custom))
    }

    @Test fun insertsMeshTabIntoLegacyInspectorLeaf() {
        val legacy = DockNode(modules = listOf("layers", "parameters", "tools", "inspector", "animation", "physics"))
        val updated = ensureMeshDockTab(legacy)
        assertEquals(
            listOf("layers", "parameters", "tools", "mesh", "inspector", "animation", "physics"),
            updated.modules,
        )
        assertSame(updated, ensureMeshDockTab(updated))
    }

    @Test fun insertsAnimationEditorTabBesideTheLog() {
        assertEquals(listOf("log", "animationEditor"), defaultDockLayout().containing("log")?.modules)
        val legacy = DockNode(modules = listOf("log"))
        val updated = ensureAnimationEditorDockTab(legacy)
        assertEquals(listOf("log", "animationEditor"), updated.modules)
        assertSame(updated, ensureAnimationEditorDockTab(updated))
        val withoutLog = DockNode(modules = listOf("canvas"))
        assertSame(withoutLog, ensureAnimationEditorDockTab(withoutLog))
    }

    @Test fun everyPresetDocksEachPanelOnceAndShowsItsCanvases() {
        WorkspacePreset.entries.forEach { preset ->
            val workspace = presetEditorWorkspace("w", preset)
            val layout = presetDockLayout(workspace)
            val modules = layout.allModules()
            val canvasIds = workspace.canvases.map { it.id }

            assertEquals(modules.size, modules.toSet().size, "$preset docks a panel twice")
            assertEquals(DEFAULT_DOCK_MODULES - PRIMARY_CANVAS_ID + canvasIds, modules.toSet(), "$preset")
            if (preset == WorkspacePreset.BLANK) return@forEach
            assertTrue(preset.hiddenModules.none(::isCanvasModule), "$preset")
            // Legacy repair must leave a preset layout alone, and every panel must have a place to reappear.
            assertSame(layout, repairLegacyCanvasDocking(layout))
            assertSame(layout, reconcileDockModules(layout, canvasIds, emptyList()))
            val visible = preset.hiddenModules.fold<String, DockNode?>(layout) { node, module -> node?.remove(module) }
            assertTrue(visible?.allModules()?.containsAll(canvasIds) == true, "$preset")
        }
    }

    @Test fun blankPresetShowsNothingButKeepsEveryPanelsPlace() {
        val (visible, workspace) = presetVisibleLayout(WorkspacePreset.BLANK)
        assertEquals(null, visible)
        assertEquals(listOf(CanvasMode.EDIT), workspace.canvases.map { it.mode })
        assertEquals(defaultDockLayout().allModules().toSet(), presetDockLayout(workspace).allModules().toSet())
        WorkspacePreset.entries.filter { it != WorkspacePreset.BLANK }.forEach { preset ->
            assertTrue(presetVisibleLayout(preset).first?.allModules()?.any(::isCanvasModule) == true, "$preset")
        }
    }

    @Test fun sidebarsAreTheRegionsAroundTheCanvases() {
        assertEquals(
            mapOf(
                SidebarSide.RIGHT to listOf("settings", "layers", "parameters", "tools", "mesh", "inspector", "animation", "physics"),
                SidebarSide.LEFT to listOf("hierarchy", "skeleton"),
                SidebarSide.BOTTOM to listOf("log", "animationEditor"),
            ),
            dockSidebars(defaultDockLayout()),
        )
        // Physics puts its canvas on the left edge: there is no left sidebar to toggle.
        val physics = presetEditorWorkspace("w", WorkspacePreset.PHYSICS).sidebars()
        assertEquals(setOf(SidebarSide.RIGHT, SidebarSide.BOTTOM), physics.keys)
        // A panel docked above the canvas is a top sidebar; a tab beside the canvas is not a sidebar.
        val canvasWithLog = DockNode(modules = listOf("canvas", "log"))
        val withTop = dockModule(canvasWithLog, "history", canvasWithLog.id, DockSide.TOP)
        assertEquals(mapOf(SidebarSide.TOP to listOf("history")), dockSidebars(withTop))
        assertTrue(dockSidebars(DockNode(modules = listOf("canvas", "log"))).isEmpty())
        // Two canvases split apart: the split holding both is the canvas area.
        val twoCanvases = presetEditorWorkspace("w", WorkspacePreset.RIG)
        assertTrue(twoCanvases.sidebars().values.flatten().none(::isCanvasModule))
        WorkspacePreset.entries.forEach { preset ->
            val workspace = presetEditorWorkspace("w", preset)
            val sides = workspace.sidebars().values.flatten()
            assertEquals(sides.size, sides.toSet().size, "$preset")
        }
    }

    @Test fun presetLayoutFillsCanvasSlotsFromTheCanvasesThatRemain() {
        val rig = presetEditorWorkspace("w", WorkspacePreset.RIG)
        val onlyEdit = rig.copy(canvases = rig.canvases.filter { it.mode == CanvasMode.EDIT })
        assertEquals(listOf(PRIMARY_CANVAS_ID), presetDockLayout(onlyEdit).allModules().filter(::isCanvasModule))

        val swapped = rig.copy(canvases = rig.canvases.reversed())
        assertEquals(
            rig.canvases.map { it.id },
            presetDockLayout(swapped).allModules().filter(::isCanvasModule),
        )
    }
}
