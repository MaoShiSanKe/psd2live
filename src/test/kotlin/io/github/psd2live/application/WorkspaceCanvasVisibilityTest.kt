package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCanvasVisibilityTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private val first = CanvasAddress("main", "canvas", CanvasViewMode.EDIT)
    private val firstPreview = CanvasAddress("main", "canvas", CanvasViewMode.PREVIEW)
    private val second = CanvasAddress("main", "canvas:2", CanvasViewMode.EDIT)
    private val otherWorkspace = CanvasAddress("rig", "canvas", CanvasViewMode.EDIT)
    private val addresses = setOf(first, firstPreview, second, otherWorkspace)
    private val scope = CanvasVisibilityScope(listOf(CanvasLayer("a", true), CanvasLayer("b", true), CanvasLayer("eye", true),
        CanvasLayer("off", false)), setOf("a", "b", "eye", "off", "eye:l"), setOf("warp"))

    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> =
        WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also { simulationFixture(it) }
    private fun records(runtime: WorkspaceRuntime<RigPreviewModel>) = CanvasVisibilityCodec.decode(runtime.capture().auxiliary)

    @Test fun processorMatchesTheHierarchyMenuAndKeepsTheFirstSoloSnapshot() {
        val edit = CanvasVisibilityProcessor::apply
        val hidden = edit(CanvasVisibility(deformers = mapOf("warp" to false)), CanvasVisibilityIntent.Layers(mapOf("a" to false, "eye" to false)), scope)
        assertEquals(mapOf("a" to false, "eye" to false), hidden.layers); assertEquals(mapOf("warp" to false), hidden.deformers)
        // A mirrored half follows its source entry; a hidden source layer inverts to visible.
        assertFalse(hidden.layerVisible("eye:l", true))
        assertEquals(mapOf("a" to true, "b" to false, "eye" to true, "off" to true),
            edit(hidden, CanvasVisibilityIntent.InvertLayers, scope).layers)
        val solo = edit(hidden, CanvasVisibilityIntent.Solo("b"), scope)
        assertEquals("b", solo.isolatedLayerId); assertEquals(hidden.layers, solo.isolationSnapshot)
        assertEquals(listOf("a", "eye", "off"), CanvasVisibilityProcessor.hiddenLayerIds(solo, scope))
        val moved = edit(solo, CanvasVisibilityIntent.ToggleSolo("a"), scope)
        assertEquals("a", moved.isolatedLayerId); assertEquals(hidden.layers, moved.isolationSnapshot)
        assertEquals(hidden, edit(moved, CanvasVisibilityIntent.ToggleSolo("a"), scope))
        assertEquals(hidden, edit(moved, CanvasVisibilityIntent.Unsolo, scope))
        assertSame(hidden, edit(hidden, CanvasVisibilityIntent.Unsolo, scope))
        // An explicit choice ends a solo without restoring the snapshot; deformer edits keep it.
        val ended = edit(moved, CanvasVisibilityIntent.Layers(mapOf("b" to true)), scope)
        assertNull(ended.isolatedLayerId); assertNull(ended.isolationSnapshot); assertEquals(false, ended.layers["eye"])
        assertEquals("a", edit(moved, CanvasVisibilityIntent.Deformers(mapOf("warp" to true)), scope).isolatedLayerId)
        assertEquals(scope.layers.associate { it.id to false }, edit(moved, CanvasVisibilityIntent.AllLayers(false), scope).layers)
        assertEquals(CanvasVisibility(mapOf("a" to true)), edit(CanvasVisibility(), CanvasVisibilityIntent.Layers(mapOf("a" to true)), null))
        for (invalid in listOf(CanvasVisibilityIntent.Layers(mapOf("missing" to true)), CanvasVisibilityIntent.Layers(emptyMap()),
            CanvasVisibilityIntent.Deformers(mapOf("missing" to true)), CanvasVisibilityIntent.Solo("missing")))
            assertFailsWith<IllegalArgumentException> { edit(CanvasVisibility(), invalid, scope) }
        assertFailsWith<IllegalArgumentException> { edit(CanvasVisibility(), CanvasVisibilityIntent.AllLayers(true), null) }
        val stale = CanvasVisibility(isolatedLayerId = "gone", isolationSnapshot = emptyMap())
        assertEquals(CanvasVisibility(), CanvasVisibilityProcessor.normalize(stale, scope))
    }

    @Test fun endingSolosKeepsWhatEachCanvasShowsAndLeavesUnsoloedAuxiliaryBytesAlone() {
        val solo = CanvasVisibilityProcessor.apply(CanvasVisibility(mapOf("a" to false), mapOf("warp" to false)), CanvasVisibilityIntent.Solo("b"), scope)
        val ended = CanvasVisibilityProcessor.endSolo(solo)
        assertEquals(CanvasVisibility(solo.layers, mapOf("warp" to false)), ended)
        assertEquals(CanvasVisibilityProcessor.hiddenLayerIds(solo, scope), CanvasVisibilityProcessor.hiddenLayerIds(ended, scope))
        val plain = CanvasVisibility(mapOf("a" to false))
        assertSame(plain, CanvasVisibilityProcessor.endSolo(plain))
        val auxiliary = CanvasVisibilityCodec.withRecords(buildJsonObject { put("extension", "keep") }, mapOf(first to solo, second to plain))
        val cleared = CanvasVisibilityProcessor.endSolos(auxiliary)
        assertEquals(mapOf(first to ended, second to plain), CanvasVisibilityCodec.decode(cleared))
        assertEquals(JsonPrimitive("keep"), cleared["extension"])
        assertSame(cleared, CanvasVisibilityProcessor.endSolos(cleared))
        val none = buildJsonObject { put("extension", "keep") }
        assertSame(none, CanvasVisibilityProcessor.endSolos(none))
    }

    @Test fun localSoloChangesOneSessionAndNeverTheDocumentHistoryOrExport() = runBlocking<Unit> {
        val runtime = fixture(); val commands = WorkspaceCanvasVisibilityCommands(runtime); val before = runtime.capture()
        val history = runtime.history()
        val layer = before.model.analysis.layers.single().source.id.raw
        val hidden = commands.edit(before.projectId, before.state, second, addresses, CanvasVisibilityIntent.Layers(mapOf(layer to false)))
        var projected: Pair<CanvasVisibility, Boolean>? = null
        val solo = commands.edit(before.projectId, hidden.state, first, addresses, CanvasVisibilityIntent.Solo(layer)) { capture, value, changed ->
            assertEquals(hidden.state, capture.state); projected = value to changed
        }
        assertEquals(CanvasVisibility(mapOf(layer to true), isolatedLayerId = layer, isolationSnapshot = emptyMap()) to true, projected)
        assertEquals(mapOf(first to solo.canvases.getValue(first), second to CanvasVisibility(mapOf(layer to false))), records(runtime))
        assertEquals(CanvasVisibility(), solo.canvases.getValue(firstPreview)); assertEquals(CanvasVisibility(), solo.canvases.getValue(otherWorkspace))
        val after = runtime.capture()
        assertEquals(before.document, after.document); assertEquals(before.revision, after.revision)
        assertEquals(before.historyHead, after.historyHead); assertEquals(history, runtime.history())
        assertSame(before.model, after.model); assertTrue(after.dirty)
        assertTrue(after.document.layerVisibility.isEmpty()); assertTrue(after.document.config().layerVisibility.isEmpty())
        // A later rig edit captures the document only; the canvas records ride along untouched.
        val edited = WorkspaceDocumentCommands(runtime).execute(after.projectId, after.state, "Parameter", listOf(WorkspaceDocumentOperation(
            "parameter_create", buildJsonObject { put("parameter_id", "Axis"); put("name", "Axis"); put("min", -1); put("max", 1) })), MutationAuthor.USER)
        assertTrue(edited.capture.document.layerVisibility.isEmpty())
        assertEquals(records(runtime).getValue(first), solo.canvases.getValue(first))
    }

    @Test fun conflictsUnknownCanvasesNoopsAndRejectedProjectionsPublishNothing() = runBlocking<Unit> {
        val runtime = fixture(); val commands = WorkspaceCanvasVisibilityCommands(runtime); val before = runtime.capture()
        val layer = before.model.analysis.layers.single().source.id.raw
        val foreign = runtime.updateAuxiliary(before.projectId, before.state, JsonObject(before.auxiliary + ("extension" to JsonPrimitive("keep"))))
        assertFailsWith<WorkspaceConflict> { commands.edit(before.projectId, before.state, first, addresses, CanvasVisibilityIntent.AllLayers(false)) }
        assertFailsWith<IllegalArgumentException> { commands.edit("other", foreign.state, first, addresses, CanvasVisibilityIntent.AllLayers(false)) }
        assertFailsWith<IllegalArgumentException> {
            commands.edit(foreign.projectId, foreign.state, CanvasAddress("main", "closed", CanvasViewMode.EDIT), addresses, CanvasVisibilityIntent.AllLayers(false))
        }
        assertFailsWith<IllegalArgumentException> { commands.edit(foreign.projectId, foreign.state, first, addresses, CanvasVisibilityIntent.Solo("missing")) }
        assertFailsWith<IllegalStateException> {
            commands.edit(foreign.projectId, foreign.state, first, addresses, CanvasVisibilityIntent.AllLayers(false)) { _, _, _ -> error("Projection rejected") }
        }
        assertEquals(foreign, runtime.capture())
        var calls = 0
        val noop = commands.edit(foreign.projectId, foreign.state, first, addresses, CanvasVisibilityIntent.Unsolo) { _, value, changed ->
            assertEquals(CanvasVisibility(), value); assertFalse(changed); calls++
        }
        assertEquals(1, calls); assertEquals(foreign.state, noop.state); assertEquals(foreign, runtime.capture())
        val shown = commands.edit(foreign.projectId, foreign.state, first, addresses, CanvasVisibilityIntent.Layers(mapOf(layer to true)))
        val again = commands.edit(shown.projectId, shown.state, first, addresses, CanvasVisibilityIntent.Layers(mapOf(layer to true)))
        assertEquals(shown.state, again.state)
        assertEquals(JsonPrimitive("keep"), runtime.capture().auxiliary["extension"])
        // Even the same archive installed again rejects tokens from the previous load.
        val current = runtime.capture()
        runtime.install(current.state, current.projectId, current.document, current.model, runtime.history(), current.auxiliary, discardUnsaved = true)
        assertFailsWith<WorkspaceConflict> { commands.edit(current.projectId, current.state, first, addresses, CanvasVisibilityIntent.Unsolo) }
        assertEquals(records(runtime), CanvasVisibilityCodec.decode(current.auxiliary))
    }

    @Test fun codecReadsTheV1PresentationLocationAndOmitsDefaults() {
        fun session(layers: Map<String, Boolean>, isolated: String? = null) = buildJsonObject { putJsonObject("presentation") {
            put("selectedLayerId", "a"); put("isolatedLayerId", isolated)
            putJsonObject("layerVisibility") { layers.forEach { (id, visible) -> put(id, visible) } }
            putJsonObject("deformerVisibility") { put("warp", "not-a-flag") }
            isolated?.let { putJsonObject("isolationSnapshot") { put("a", false) } }
        } }
        val ui = buildJsonObject { putJsonArray("workspaces") {
            add(buildJsonObject { put("id", "main"); putJsonArray("canvases") {
                add(buildJsonObject { put("id", "canvas"); put("mode", "PREVIEW")
                    put("editSession", session(mapOf("a" to false))); put("previewSession", session(mapOf("b" to true), "b")) })
                // Before separate mode sessions, the canvas object itself was its current mode's session.
                add(JsonObject(session(mapOf("b" to false)) + mapOf("id" to JsonPrimitive("canvas:2"), "mode" to JsonPrimitive("EDIT"))))
                add(buildJsonObject { put("id", "canvas:3"); put("editSession", session(emptyMap())) })
            } })
            add(buildJsonObject { put("id", "main"); putJsonArray("canvases") { add(buildJsonObject { put("id", "dup") }) } })
        } }
        val read = CanvasVisibilityCodec.fromPresentation(ui)
        assertEquals(mapOf(first to CanvasVisibility(mapOf("a" to false)),
            firstPreview to CanvasVisibility(mapOf("b" to true), isolatedLayerId = "b", isolationSnapshot = mapOf("a" to false)),
            second to CanvasVisibility(mapOf("b" to false))), read)
        val auxiliary = CanvasVisibilityCodec.withRecords(buildJsonObject { put("posesByWorkspace", JsonObject(emptyMap())) },
            read + (otherWorkspace to CanvasVisibility()))
        assertEquals(read, CanvasVisibilityCodec.decode(auxiliary))
        assertEquals(setOf("main"), auxiliary.getValue(CanvasVisibilityCodec.KEY).jsonObject.keys)
        assertTrue("posesByWorkspace" in auxiliary)
        assertEquals(buildJsonObject { put("posesByWorkspace", JsonObject(emptyMap())) },
            CanvasVisibilityCodec.withRecords(auxiliary, read.mapValues { CanvasVisibility() }))
        assertTrue(CanvasVisibilityCodec.fromPresentation(buildJsonObject { putJsonArray("workspaceTabs") {} }).isEmpty())
    }

    @Test fun archiveRoundTripKeepsPresentationAtItsV1LocationAndTheDocumentClean() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val layer = before.model.analysis.layers.single().source.id.raw
        val ui = buildJsonObject {
            put("activeWorkspaceId", "main")
            putJsonArray("workspaces") { add(buildJsonObject { put("id", "main"); putJsonArray("canvases") {
                add(buildJsonObject { put("id", "canvas"); put("mode", "EDIT")
                    putJsonObject("editSession") { putJsonObject("presentation") {
                        put("isolatedLayerId", layer); putJsonObject("layerVisibility") { put(layer, true) }
                        putJsonObject("isolationSnapshot") { put(layer, false) } } }
                    putJsonObject("previewSession") { putJsonObject("presentation") { putJsonObject("layerVisibility") { put(layer, false) } } } })
                add(buildJsonObject { put("id", "canvas:2"); put("mode", "EDIT") })
            } }) }
        }
        val file = temporary.resolve("visibility.psd2live")
        ProjectRepository().save(ProjectSaveCapture(before.projectId, runtime.history(), ui, null, WorkspaceStore(temporary.resolve("store"))), file)
        val (reopened, presentation, tree) = ProjectRepository().open(file).use { Triple(it.history.head().snapshot, it.presentation, it.history) }
        assertEquals(ui.getValue("workspaces"), presentation.getValue("workspaces"))
        val records = CanvasVisibilityCodec.fromPresentation(presentation)
        val installed = runtime.install(before.state, before.projectId, reopened, builder.build(reopened), tree.state(),
            CanvasVisibilityCodec.withRecords(before.auxiliary, records), discardUnsaved = true)
        val commands = WorkspaceCanvasVisibilityCommands(runtime)
        val read = commands.snapshot(installed, addresses)
        assertEquals(layer, read.canvases.getValue(first).isolatedLayerId)
        assertEquals(listOf(layer), CanvasVisibilityProcessor.hiddenLayerIds(read.canvases.getValue(firstPreview), read.scope))
        val restored = commands.edit(installed.projectId, installed.state, first, addresses, CanvasVisibilityIntent.ToggleSolo(layer))
        assertEquals(CanvasVisibility(mapOf(layer to false)), restored.canvases.getValue(first))
        assertEquals(CanvasVisibility(mapOf(layer to false)), restored.canvases.getValue(firstPreview))
        assertEquals(CanvasVisibility(), restored.canvases.getValue(second))
        assertEquals(installed.document, runtime.capture().document); assertEquals(installed.historyHead, runtime.capture().historyHead)
        assertTrue(runtime.capture().document.layerVisibility.isEmpty())
    }

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val addresses: Set<CanvasAddress>) : WorkspaceBackendStub() {
        val commands = WorkspaceCanvasVisibilityCommands(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun canvasVisibility() = commands.snapshot(runtime.capture(), addresses)
        override fun editCanvasVisibility(state: String, address: CanvasAddress, intent: CanvasVisibilityIntent) =
            commands.edit(runtime.capture().projectId, state, address, addresses, intent)
    }

    @Test fun publicOperationsUseStrictRequestAndResultContracts() = runBlocking<Unit> {
        val runtime = fixture(); val layer = runtime.capture().model.analysis.layers.single().source.id.raw
        WorkspaceOperations(Host(runtime, addresses)).use { operations ->
            val control = operations.registry.definition("canvas_visibility")
            assertEquals(WorkspaceOperationKind.SESSION, control.kind); assertFalse(control.batchable); assertFalse(control.jobBacked)
            assertEquals(WorkspaceOperationKind.QUERY, operations.registry.definition("canvas_visibility_get").kind)
            fun request(fields: JsonObject, id: String) = JsonObject(fields + buildJsonObject {
                val capture = runtime.capture(); put("state", capture.state); put("project_id", capture.projectId); put("request_id", id)
                put("workspace_id", "main"); put("canvas_id", "canvas:2"); put("mode", "edit")
            })
            suspend fun call(id: String, request: JsonObject) = operations.registry.invoke(id, request, agent).data
            val solo = call("canvas_visibility", request(buildJsonObject { put("action", "solo"); put("layer_id", layer) }, "solo"))
            assertEquals(JsonPrimitive(true), solo["applied"])
            val canvas = solo.getValue("canvas").jsonObject
            assertEquals(JsonPrimitive(layer), canvas["isolated_layer_id"]); assertEquals(JsonPrimitive("canvas:2"), canvas["canvas_id"])
            assertEquals(runtime.capture().state, solo.getValue("state").jsonPrimitive.content)
            val repeated = call("canvas_visibility", request(buildJsonObject { put("action", "solo"); put("layer_id", layer) }, "repeat"))
            assertEquals(JsonPrimitive(false), repeated["applied"]); assertEquals(solo["state"], repeated["state"])
            val items = call("canvas_visibility_get", buildJsonObject { put("canvas_id", "canvas:2") }).getValue("items").jsonArray
            assertEquals(listOf("canvas:2"), items.map { it.jsonObject.getValue("canvas_id").jsonPrimitive.content })
            assertEquals(4, call("canvas_visibility_get", JsonObject(emptyMap())).getValue("items").jsonArray.size)
            for (invalid in listOf(buildJsonObject { put("action", "solo") }, buildJsonObject { put("action", "unsolo"); put("layer_id", layer) },
                buildJsonObject { put("action", "layers"); putJsonObject("layers") {} }, buildJsonObject { put("action", "all_layers"); put("visible", "yes") },
                buildJsonObject { put("action", "invert_layers"); put("unexpected", 1) }, buildJsonObject { put("action", "show") }))
                assertFailsWith<WorkspaceValidationException> { call("canvas_visibility", request(invalid, "invalid")) }
            assertFailsWith<WorkspaceValidationException> { call("canvas_visibility_get", buildJsonObject { put("mode", "both") }) }
            val schema = WorkspaceCanvasVisibilityResultSchemas.forOperation("canvas_visibility_get")
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(items.first().jsonObject - "hidden_layer_ids"),
                WorkspaceCanvasVisibilityResultSchemas.canvas) }
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject {
                put("project_id", "p"); put("state", "s"); put("history_node_id", "h"); put("items", items); put("extra", 1) }, schema) }
        }
    }
}
