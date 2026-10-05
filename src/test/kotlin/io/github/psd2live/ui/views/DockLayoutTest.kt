package io.github.psd2live.ui.views

import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.DEFAULT_DOCK_MODULES
import io.github.psd2live.ui.state.PRIMARY_CANVAS_ID
import io.github.psd2live.ui.state.SidebarSide
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.isCanvasModule
import io.github.psd2live.ui.state.presetEditorWorkspace
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun simulationTabFollowsPhysicsInNewAndOlderLayouts() {
        WorkspacePreset.entries.forEach { preset ->
            val modules = presetDockLayout(presetEditorWorkspace("w", preset)).containing("physics")?.modules.orEmpty()
            assertEquals(modules.indexOf("physics") + 1, modules.indexOf("simulation"), "$preset")
            assertEquals("physics" in preset.hiddenModules, "simulation" in preset.hiddenModules, "$preset")
        }
        val legacy = DockNode(modules = listOf("inspector", "physics", "layers"))
        val updated = ensureSimulationDockTab(legacy)
        assertEquals(listOf("inspector", "physics", "simulation", "layers"), updated.modules)
        assertSame(updated, ensureSimulationDockTab(updated))
        assertEquals(listOf("mesh") + updated.modules, repairLegacyCanvasDocking(legacy).modules)
    }

    @Test fun olderWorkspaceHidingPhysicsAlsoHidesTheSimulationTab() {
        val state = io.github.psd2live.ui.state.PSD2LiveState()
        val encoded = io.github.psd2live.ui.state.WorkspaceStateCodec.encode(state)
        fun withWorkspace(layout: String, hidden: List<String>) = kotlinx.serialization.json.JsonObject(encoded + ("workspaces" to
            kotlinx.serialization.json.JsonArray(encoded.getValue("workspaces").let { it as kotlinx.serialization.json.JsonArray }.map { workspace ->
                kotlinx.serialization.json.JsonObject(workspace as kotlinx.serialization.json.JsonObject +
                    ("layout" to kotlinx.serialization.json.JsonPrimitive(layout)) +
                    ("hiddenModules" to kotlinx.serialization.json.JsonArray(hidden.map(::JsonPrimitive))))
            })))
        val old = dockJson.encodeToString(DockNode.serializer(), DockNode(modules = listOf("canvas", "physics")))
        fun hidden(layout: String, hidden: List<String>) =
            io.github.psd2live.ui.state.WorkspaceStateCodec.decode(withWorkspace(layout, hidden)).activeWorkspace.hiddenModules
        assertEquals(setOf("physics", "simulation"), hidden(old, listOf("physics")))
        assertEquals(emptySet(), hidden(old, emptyList()))
        val current = dockJson.encodeToString(DockNode.serializer(), DockNode(modules = listOf("canvas", "physics", "simulation")))
        assertEquals(setOf("physics"), hidden(current, listOf("physics")))
    }

    @Test fun everyPresetDocksEachPanelOnceAndShowsItsCanvases() {
        WorkspacePreset.entries.forEach { preset ->
            val workspace = presetEditorWorkspace("w", preset)
            val layout = presetDockLayout(workspace)
            val modules = layout.allModules()
            val canvasIds = workspace.canvases.map { it.id }

            assertEquals(modules.size, modules.toSet().size, "$preset docks a panel twice")
            val extra = if (preset == WorkspacePreset.HISTORY) setOf("history") else emptySet()
            assertEquals(DEFAULT_DOCK_MODULES - PRIMARY_CANVAS_ID + canvasIds + extra, modules.toSet(), "$preset")
            if (preset == WorkspacePreset.BLANK || preset == WorkspacePreset.HISTORY) return@forEach
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
        WorkspacePreset.entries.filter { it != WorkspacePreset.BLANK && it != WorkspacePreset.HISTORY }.forEach { preset ->
            assertTrue(presetVisibleLayout(preset).first?.allModules()?.any(::isCanvasModule) == true, "$preset")
        }
    }

    @Test fun historyPresetShowsOnlyTheHistoryTree() {
        val (visible, workspace) = presetVisibleLayout(WorkspacePreset.HISTORY)
        assertEquals(listOf("history"), visible?.allModules())
        assertTrue(workspace.canvases.all { it.id in workspace.hiddenModules })
        assertEquals(defaultDockLayout().allModules().toSet() + "history", presetDockLayout(workspace).allModules().toSet())
    }

    @Test fun sidebarsAreTheRegionsAroundTheCanvases() {
        assertEquals(
            mapOf(
                SidebarSide.RIGHT to listOf("settings", "layers", "parameters", "tools", "mesh", "inspector", "animation", "physics", "simulation"),
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

    @Test fun tabsReorderWithinAGroupAndInsertAtAPositionInAnother() {
        val group = DockNode(modules = listOf("layers", "parameters", "tools"))
        val other = DockNode(modules = listOf("log", "history"))
        val root = DockNode(first = group, second = other)
        // Within one group: the moved tab lands before the named tab and becomes selected.
        val reordered = dockModule(root, "tools", group.id, DockSide.CENTER, before = "layers")
        assertEquals(listOf("tools", "layers", "parameters"), reordered.find(group.id)?.modules)
        assertEquals("tools", reordered.find(group.id)?.selected)
        assertEquals(listOf("parameters", "tools", "layers"),
            dockModule(root, "layers", group.id, DockSide.CENTER).find(group.id)?.modules)
        // Across groups: inserted before the named tab, or last when it is absent.
        val moved = dockModule(root, "parameters", other.id, DockSide.CENTER, before = "history")
        assertEquals(listOf("log", "parameters", "history"), moved.find(other.id)?.modules)
        assertEquals(listOf("layers", "tools"), moved.find(group.id)?.modules)
        assertEquals(listOf("log", "history", "parameters"),
            dockModule(root, "parameters", other.id, DockSide.CENTER, before = "missing").find(other.id)?.modules)
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
