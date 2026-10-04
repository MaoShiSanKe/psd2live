package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.ui.state.*
import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.Cmo3ModelImport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceAuxiliaryIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val context = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun settled(vm: PSD2LiveViewModel) {
        withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
        assertNull(vm.state.value.errorMessage)
    }

    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, WorkspaceOperations) -> Unit) {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y, 0xff526080.toInt())
        val png = temporary.resolve("art.png")
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                    }) }
                })
                WorkspaceOperations(workspace).use { action(vm, workspace, it) }
            }
        }
    }

    private fun request(workspace: DesktopWorkspace, id: String, fields: JsonObject = buildJsonObject {}) = buildJsonObject {
        val captured = workspace.snapshot()
        put("request_id", id); put("project_id", captured.projectId); put("state", captured.state)
        fields.forEach { (key, value) -> put(key, value) }
    }
    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, context).data

    @Test fun guiAndMcpShareSavedSnapshotsAnnotationsAndPortablePersistence() = runBlocking {
        fixture { vm, workspace, operations ->
            val root = workspace.snapshot()
            val history = workspace.history()
            vm.saveParameterSnapshot("GUI pose")
            val gui = workspace.savedProjectData()
            assertNotEquals(root.state, gui.state)
            assertEquals(history, workspace.history())
            val page = operations.call("snapshot_list", buildJsonObject { put("limit", 1) })
            assertEquals(gui.state, page.getValue("state").jsonPrimitive.content)
            assertEquals("GUI pose", page.getValue("items").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
            val parameter = workspace.snapshot().parameters.first { it.min < it.max }
            val input = request(workspace, "create", buildJsonObject {
                put("name", "MCP pose"); putJsonObject("values") { put(parameter.id, parameter.max) }
            })
            val created = operations.call("snapshot_create", input)
            val id = created.getValue("snapshot").jsonObject.getValue("id").jsonPrimitive.content
            assertEquals(created, operations.call("snapshot_create", input))
            assertEquals(2, vm.state.value.parameterSnapshots.size)
            assertEquals(history, workspace.history())
            val state = workspace.snapshot().state
            operations.call("snapshot_update", request(workspace, "no-op", buildJsonObject { put("id", id); put("name", "MCP pose") }))
            assertEquals(state, workspace.snapshot().state)
            assertFailsWith<IllegalArgumentException> {
                operations.call("snapshot_update", request(workspace, "invalid", buildJsonObject {
                    put("id", id); putJsonObject("values") { put(parameter.id, parameter.max + 1) }
                }))
            }
            assertEquals(state, workspace.snapshot().state)
            val stale = request(workspace, "stale", buildJsonObject { put("id", id); put("name", "Old") })
            vm.renameParameterSnapshot(id, "GUI renamed")
            assertFailsWith<WorkspaceConflict> { operations.call("snapshot_update", stale) }
            operations.call("history_annotation_put", request(workspace, "annotation", buildJsonObject {
                put("node_id", root.historyHeadNodeId); put("title", "  Ready  "); put("note", "Saved pose"); put("hidden", true)
            }))
            assertEquals("Ready", vm.state.value.historyAnnotations.getValue(root.historyHeadNodeId!!).title)
            val beforeAnnotation = workspace.snapshot().state
            vm.editHistoryAnnotation(root.historyHeadNodeId, "Reviewed", "Note", false)
            assertNotEquals(beforeAnnotation, workspace.snapshot().state)
            val annotation = operations.call("history_annotation_get", buildJsonObject { put("node_id", root.historyHeadNodeId) })
            assertEquals("Reviewed", annotation.getValue("annotation").jsonObject.getValue("title").jsonPrimitive.content)
            assertEquals(history, workspace.history())
            val snapshots = vm.state.value.parameterSnapshots
            val annotations = vm.state.value.historyAnnotations
            val controller = ProjectController(vm)
            val file = temporary.resolve("saved.psd2live")
            controller.save(workspace, file)
            assertFalse(workspace.snapshot().projectDirty)
            controller.open(workspace, file)
            assertEquals(snapshots, workspace.savedProjectData().data.parameterSnapshots)
            assertEquals(annotations, workspace.savedProjectData().data.historyAnnotations)
            assertEquals(history, workspace.history())
            operations.call("snapshot_delete", request(workspace, "delete", buildJsonObject { put("id", id) }))
            assertEquals(listOf(snapshots.first()), vm.state.value.parameterSnapshots)
            operations.call("history_annotation_delete", request(workspace, "remove-annotation", buildJsonObject { put("node_id", root.historyHeadNodeId) }))
            assertTrue(vm.state.value.historyAnnotations.isEmpty())
            assertEquals(history, workspace.history())
        }
    }

    @Test fun authoredPoseChangesInvalidateRequestsButEvaluatedFramesDoNot() = runBlocking {
        fixture { vm, workspace, operations ->
            val parameter = workspace.snapshot().parameters.first { it.min < it.max }
            val axis = ParameterId(parameter.id)
            val initial = workspace.snapshot()
            val history = workspace.history()
            vm.setParameterValue(axis, parameter.max)
            settled(vm)
            assertNotEquals(initial.state, workspace.snapshot().state)
            vm.saveParameterSnapshot("Endpoint")
            val snapshot = vm.state.value.parameterSnapshots.single()
            val old = request(workspace, "old-preview", buildJsonObject { putJsonObject("values") { put(parameter.id, parameter.min) } })
            vm.setParameterValue(axis, parameter.min)
            settled(vm)
            assertFailsWith<WorkspaceConflict> { operations.call("preview_set", old) }
            vm.toggleParameterLock(axis)
            settled(vm)
            val locked = workspace.snapshot().state
            val noOp = operations.call("snapshot_apply", request(workspace, "locked", buildJsonObject { put("id", snapshot.id) }))
            assertEquals(locked, noOp.getValue("state").jsonPrimitive.content)
            assertEquals(parameter.min, vm.state.value.parameterValues.getValue(axis))
            vm.toggleParameterLock(axis)
            settled(vm)
            val input = request(workspace, "apply", buildJsonObject { put("id", snapshot.id) })
            val applied = operations.call("snapshot_apply", input)
            assertEquals(workspace.snapshot().state, applied.getValue("state").jsonPrimitive.content)
            assertNotEquals(input.getValue("state"), applied.getValue("state"))
            assertEquals(parameter.max, vm.state.value.parameterValues.getValue(axis))
            assertEquals(applied, operations.call("snapshot_apply", input))
            assertEquals(history, workspace.history())
            vm.createMotionClip()
            settled(vm)
            vm.setMotionAutoKey(true)
            operations.call("preview_set", request(workspace, "reset-before-gui", buildJsonObject { putJsonObject("values") { put(parameter.id, parameter.min) } }))
            val edits = vm.state.value.rigEdits
            val beforeApplyHistory = workspace.history()
            vm.applyParameterSnapshot(snapshot.id)
            assertEquals(parameter.max, vm.state.value.parameterValues.getValue(axis))
            assertEquals(edits, vm.state.value.rigEdits)
            assertEquals(beforeApplyHistory, workspace.history())
            val canvas = vm.state.value.activeCanvas.id
            vm.setCanvasMode(canvas, CanvasMode.PREVIEW)
            val renderKey = vm.canvasRenderKey(canvas)
            vm.sdkFrameFor(renderKey)
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, canvas, CanvasMode.PREVIEW) { it.copy(animationEnabled = true) }
            val beforeFrame = workspace.snapshot().state
            val capturedQueries = workspace.captureQueries()
            val authoredPreview = capturedQueries.previewSession()
            assertEquals(parameter.max, authoredPreview.getValue("values").jsonObject.getValue(parameter.id).jsonPrimitive.float)
            repeat(3) { index -> vm.acceptSdkFrame(CubismSdkFrame(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                mapOf(axis to parameter.min), animationEnabled = true, viewId = renderKey), 1_000_000_000L + index * 16_000_000L) }
            assertEquals(beforeFrame, workspace.snapshot().state)
            assertEquals(parameter.min, vm.livePose.value.getValue(axis))
            assertEquals(authoredPreview, capturedQueries.previewSession())
            assertEquals(authoredPreview, operations.call("workspace_inspect", buildJsonObject { put("scope", "preview") }).getValue("preview"))
            assertEquals(parameter.max, workspace.snapshot().parameters.first { it.id == parameter.id }.current)
            // An uncommitted host pose cannot silently change a query carrying the committed state token.
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + (axis to parameter.min)))
            assertEquals(beforeFrame, workspace.snapshot().state)
            assertEquals(authoredPreview, workspace.previewSession())
            assertEquals(parameter.max, workspace.snapshot().parameters.first { it.id == parameter.id }.current)
            assertEquals(beforeApplyHistory, workspace.history())
        }
    }

    @Test fun partialPreviewAndSnapshotApplicationKeepCommittedValuesAndLocksAcrossHostDriftAndArchive() = runBlocking<Unit> {
        fixture { vm, workspace, operations ->
            val parameters = workspace.snapshot().parameters.filter { it.min < it.max }.take(2)
            assertEquals(2, parameters.size)
            val first = parameters[0]; val second = parameters[1]
            val a = ParameterId(first.id); val b = ParameterId(second.id)
            val history = workspace.history()
            operations.call("preview_set", request(workspace, "authored", buildJsonObject {
                putJsonObject("values") { put(first.id, first.max); put(second.id, second.max) }
                putJsonObject("locks") { put(first.id, true) }
            }))
            val firstWorkspace = vm.state.value.activeWorkspace.id
            val duplicatedWorkspace = vm.duplicateWorkspace()
            vm.setActiveWorkspace(firstWorkspace)
            vm.saveParameterSnapshot("Saved authored pose")
            val saved = vm.state.value.parameterSnapshots.single()
            val captured = workspace.captureQueries()
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + mapOf(a to first.min, b to second.min), lockedParameters = emptySet()))
            val midpoint = (second.min + second.max) * 0.5f
            operations.call("preview_set", request(workspace, "partial", buildJsonObject { putJsonObject("values") { put(second.id, midpoint) } }))
            assertEquals(first.max, vm.state.value.parameterValues.getValue(a)); assertEquals(midpoint, vm.state.value.parameterValues.getValue(b))
            assertTrue(a in vm.state.value.lockedParameters)
            assertEquals(first.max, workspace.snapshot().parameters.first { it.id == first.id }.current)
            assertEquals(second.max, captured.previewSession().getValue("values").jsonObject.getValue(second.id).jsonPrimitive.float)
            val quarter = second.min + (second.max - second.min) * 0.25f
            val beforeActualCommitVersion = vm.state.value.projectEditVersion
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + (b to quarter), projectDirty = false))
            operations.call("preview_set", request(workspace, "matching-display", buildJsonObject { putJsonObject("values") { put(second.id, quarter) } }))
            assertTrue(vm.state.value.projectDirty)
            assertEquals(beforeActualCommitVersion + 1, vm.state.value.projectEditVersion)
            assertEquals(quarter, workspace.snapshot().parameters.first { it.id == second.id }.current)
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + (a to first.min), lockedParameters = emptySet()))
            operations.call("snapshot_apply", request(workspace, "snapshot", buildJsonObject { put("id", saved.id) }))
            assertEquals(first.max, vm.state.value.parameterValues.getValue(a)); assertEquals(second.max, vm.state.value.parameterValues.getValue(b))
            assertTrue(a in vm.state.value.lockedParameters)
            val state = workspace.snapshot().state; val version = vm.state.value.projectEditVersion
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + (a to first.min), lockedParameters = emptySet(), animationEnabled = true))
            val noopInput = request(workspace, "noop", buildJsonObject { putJsonObject("values") { put(first.id, first.max) } })
            val noop = operations.call("preview_set", noopInput)
            assertEquals(state, noop.getValue("state").jsonPrimitive.content)
            assertEquals(first.max, vm.state.value.parameterValues.getValue(a)); assertTrue(a in vm.state.value.lockedParameters)
            assertFalse(vm.state.value.animationEnabled); assertEquals(version, vm.state.value.projectEditVersion)
            assertEquals(noop, operations.call("preview_set", noopInput)); assertEquals(history, workspace.history())
            val view = WorkspaceModelViewRequest(parameters = workspace.snapshot().parameters.associate { it.id to it.current },
                frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = WorkspaceViewOutputSpec(128))
            val png = workspace.renderModel(view).png
            val archive = temporary.resolve("preview.psd2live")
            val authored = workspace.previewSession()
            // Saving projects the committed pose even if the host has an evaluated or unfinished value.
            vm.setStateForTest(vm.state.value.copy(parameterValues = vm.state.value.parameterValues + mapOf(a to first.min, b to second.min),
                lockedParameters = emptySet(), previewParameterValues = mapOf(a to first.min),
                workspaces = vm.state.value.workspaces.map { item -> if (item.id == duplicatedWorkspace)
                    item.withPose(item.pose!!.copy(parameterValues = mapOf(a to first.min), lockedParameters = emptySet())) else item }))
            ProjectController(vm).save(workspace, archive)
            assertEquals(authored, workspace.previewSession())
            ProjectController(vm).open(workspace, archive)
            assertEquals(authored, workspace.previewSession()); assertEquals(history, workspace.history())
            val duplicate = vm.state.value.workspaces.single { it.id == duplicatedWorkspace }.pose!!
            assertEquals(first.max, duplicate.parameterValues.getValue(a)); assertEquals(second.max, duplicate.parameterValues.getValue(b))
            assertTrue(a in duplicate.lockedParameters); assertTrue(duplicate.previewParameterValues.isEmpty())
            assertContentEquals(png, workspace.renderModel(view).png)
            val exported = workspace.exportModel(workspace.snapshot().state, temporary.resolve("export").toString())
                .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            val imported = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.toString().endsWith(".cmo3") })).puppet
            assertEquals(workspace.currentPuppet()!!.parameters.map { listOf(it.id.raw, it.min, it.max, it.default) },
                imported.parameters.map { listOf(it.id.raw, it.min, it.max, it.default) })
            assertEquals(authored, workspace.previewSession()); assertEquals(history, workspace.history())
            val visuals = Path.of("build/preview-command-visual").toAbsolutePath(); Files.createDirectories(visuals)
            Files.write(visuals.resolve("authored.png"), png); Files.write(visuals.resolve("reopened.png"), workspace.renderModel(view).png)
            operations.call("preview_reset", request(workspace, "reset"))
            assertTrue(vm.state.value.lockedParameters.isEmpty())
            workspace.snapshot().parameters.forEach { assertEquals(it.default, it.current) }
            assertEquals(history, workspace.history())
        }
    }
}
