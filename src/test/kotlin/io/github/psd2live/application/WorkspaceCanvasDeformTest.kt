package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.renderOrder
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCanvasDeformTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val actor = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val afterCommit: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author)
            afterCommit()
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private suspend fun fixture(imported: Boolean = false, rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        fun layer(id: String, order: Int) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
            LayerBounds(8, 8, 48, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(48, 48, ByteArray(48 * 48 * 4) { if (it % 4 == 3) -1 else (80 + order * 30).toByte() }), null, null, false)
        val source = WorkspaceSourceArt(64, 64, listOf(layer("a", 1), layer("b", 2)), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), source.layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) },
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime(rebuild)
        runtime.install(runtime.state.value.state, "stroke", document, builder.build(document))
        val before = runtime.capture(); val ids = before.model.rig.puppet.drawables.map { it.id.raw }
        val authored = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Authored mesh", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", 0); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "Parent"); put("name", "Parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { ids.forEach { add(it) } } }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Seam"); put("mesh_a", ids[0]); put("mesh_b", ids[1]); put("distance", 100) }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                ids.forEach { id -> add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:$id"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(before.model.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions.size) { JsonPrimitive(0.02f) })) }
                    putJsonObject("channels") { put("opacity", 0.65) }
                }) }
            } })
        ), MutationAuthor.USER).capture
        if (imported) {
            val cmo = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(authored.model.rig.puppet),
                authored.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) }, authored.model.rig.pageByDrawableId, "Synthetic brush", 0, 0x42)
            val (incoming, settings) = Cmo3ModelImport.prepare(Cmo3.write(cmo.model), Cmo3ImportMode.NEW, null, authored.model.config)
            val importedDocument = WorkspaceDocument(incoming, settings.layerVisibility, emptySet(), settings.layerOverrides,
                settings.parentOverrides, settings.rigEdits, WorkspaceSettingsCodec.encode(settings))
            runtime.install(authored.state, "imported-stroke", importedDocument, builder.build(importedDocument), discardUnsaved = true)
        }
        return runtime
    }
    private fun stroke(model: PuppetModel, action: CanvasDeformStroke.Action = CanvasDeformStroke.Action.BRUSH): WorkspaceDocumentOperation {
        val pose = mapOf("Shape" to 1f)
        val id = model.drawables.first().id.raw
        val world = CpuDeformationEvaluator().evaluate(model, mapOf(ParameterId("Shape") to 1f)).worldPositions.getValue(DrawableId(id))
        val request = CanvasDeformStroke.Request(action, CanvasDeformStroke.Mode.DEFORM,
            listOf(CanvasDeformStroke.Target("mesh", id, pose, setOf(0))), pose,
            CanvasBrushTip(80f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 0.5f, false)
        return WorkspaceCanvasDeformEdits.operation(request, listOf(CanvasDeformStroke.Sample(CanvasBrushPoint(world[0], -world[1])),
            CanvasDeformStroke.Sample(CanvasBrushPoint(world[0] + 4f, -world[1] + 3f)), CanvasDeformStroke.Sample(CanvasBrushPoint(world[0] + 8f, -world[1] + 6f))))
    }
    private fun input(capture: WorkspaceCapture<RigPreviewModel>, operation: WorkspaceDocumentOperation, requestId: String) = JsonObject(operation.request + buildJsonObject {
        put("state", capture.state); put("project_id", capture.projectId); put("request_id", requestId)
    })
    private suspend fun WorkspaceOperations.call(id: String, fields: JsonObject) = registry.invoke(id, fields, actor).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private fun samePoses(expected: PuppetModel, actual: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        assertEquals(expected.drawables.map { it.id }.toSet(), actual.drawables.map { it.id }.toSet())
        for (value in listOf(0f, 0.35f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to value)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            a.worldPositions.forEach { (id, points) ->
                val result = b.worldPositions.getValue(id); assertEquals(points.size, result.size)
                points.indices.forEach { assertEquals(points[it], result[it], 0.002f, "${id.raw} at $value / $it") }
            }
            a.opacity.forEach { (id, opacity) -> assertEquals(opacity, b.opacity.getValue(id), 0.0001f) }
            a.drawOrder.forEach { (id, order) -> assertEquals(order, b.drawOrder.getValue(id), 0.0001f) }
            assertEquals(renderOrder(expected.renderRoot, a.drawOrder), renderOrder(actual.renderRoot, b.drawOrder), "Actual paint order at Shape=$value")
        }
    }

    @Test fun publicStrokeUsesOneCandidateAndSurvivesOrdinaryImportedHistoryArchiveExportsAndVisiblePng() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val runtime = fixture(imported); val before = runtime.capture(); val operation = stroke(before.model.rig.puppet)
            val expected = RigAuthoringJournal.compile(before.model.rig.puppet, WorkspaceCanvasDeformEdits.commands(before.model.rig.puppet, operation)).first
            WorkspaceOperations(Host(runtime)).use { operations ->
                val definition = operations.registry.definition(operation.operation)
                assertTrue(definition.batchable); assertFalse(definition.jobBacked)
                val request = input(before, operation, "stroke")
                val result = operations.call(operation.operation, request)
                validateOperationSchema(result, definition.resultSchema!!)
                assertEquals(result, operations.call(operation.operation, request))
                val after = runtime.capture(); samePoses(expected, after.model.rig.puppet)
                assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
                assertEquals(before.document.rigEdits.authoringJournal.size + 2, after.document.rigEdits.authoringJournal.size, "Direct target and welded partner share one history candidate")
                before.model.rig.puppet.drawables.zip(after.model.rig.puppet.drawables).forEach { (old, fresh) ->
                    assertContentEquals(old.mesh!!.uvs, fresh.mesh!!.uvs); assertContentEquals(old.mesh.indices, fresh.mesh.indices)
                }
                samePoses(after.model.rig.puppet, builder.build(after.document).rig.puppet)
                val archive = temporary.resolve("deform-$imported.psd2live")
                ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
                    WorkspaceStore(temporary.resolve("store-$imported"))), archive)
                val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
                samePoses(after.model.rig.puppet, reopened.rig.puppet)
                fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "stroke", mapOf("Shape" to 1f),
                    model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 64f, 64f)),
                    WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
                val png = render(after.model); assertContentEquals(png, render(reopened))
                val image = javax.imageio.ImageIO.read(png.inputStream())
                assertTrue((0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) ushr 24 != 0 } } > 1000)
                val visible = Path.of("build/canvas-deform-proof"); Files.createDirectories(visible); Files.write(visible.resolve("$imported-after.png"), png)
                Files.write(visible.resolve("$imported-before.png"), render(before.model))
                val exported = PSD2LivePipeline().run(after.document.source, "stroke", temporary.resolve("export-$imported"), after.document.config().copy(exportMoc3 = true)).exportedFiles
                val cmo = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
                val moc = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".moc3") }.path)), null)
                samePoses(after.model.rig.puppet, cmo); samePoses(after.model.rig.puppet, moc)
                runtime.checkout(after.projectId, after.state, before.historyHead)
                assertEquals(before.document, runtime.capture().document)
                runtime.checkout(after.projectId, runtime.capture().state, after.historyHead); samePoses(after.model.rig.puppet, runtime.capture().model.rig.puppet)
            }
        }
    }

    @Test fun invalidLaterBatchCancellationAndConcurrentCommitNeverPublishStrokePrefixes() = runBlocking<Unit> {
        var entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var block = false
        val runtime = fixture(rebuild = { document -> if (block) { entered.complete(Unit); release.await() }; builder.build(document) })
        val before = runtime.capture(); val history = runtime.history(); val operation = stroke(before.model.rig.puppet)
        WorkspaceOperations(Host(runtime)).use { operations ->
            suspend fun batch(requestId: String, edits: List<WorkspaceDocumentOperation>) = operations.call("workspace_apply_edits", buildJsonObject {
                put("state", before.state); put("project_id", before.projectId); put("request_id", requestId)
                put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } }))
            })
            val badStroke = JsonObject(operation.request.getValue("stroke").jsonObject + ("targets" to buildJsonArray { add(buildJsonObject { put("target", "mesh:missing") }) }))
            val bad = WorkspaceDocumentOperation(operation.operation, buildJsonObject { put("stroke", badStroke) })
            val failed = operations.wait(batch("late-invalid", listOf(operation, bad)))
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertEquals(1, failed.getValue("error").jsonObject.getValue("edit_index").jsonPrimitive.int)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            block = true
            val job = batch("cancel", listOf(operation)); withTimeout(5000) { entered.await() }
            operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel-job") })
            assertEquals("cancelled", operations.wait(job).getValue("status").jsonPrimitive.content)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            // The already-entered latch still blocks another candidate; an unrelated durable edit wins its CAS.
            entered = CompletableDeferred()
            supervisorScope {
                val pending = async { WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Concurrent stroke", listOf(operation), MutationAuthor.USER) }
                withTimeout(5000) { entered.await() }
                val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                assertFailsWith<WorkspaceConflict> { pending.await() }
                assertEquals(foreign, runtime.capture()); assertEquals(history, runtime.history())
            }
            assertFailsWith<WorkspaceConflict> { operations.call(operation.operation, input(before, operation, "stale")) }
        }
    }

    @Test fun missedZeroStrengthAndEmptySelectionStrokesAreNoChangesWithoutRebuildOrHistory() = runBlocking<Unit> {
        var rebuilds = 0
        val runtime = fixture(rebuild = { rebuilds++; builder.build(it) }); val before = runtime.capture(); val history = runtime.history()
        val operation = stroke(before.model.rig.puppet); val base = operation.request.getValue("stroke").jsonObject
        val originalTarget = base.getValue("targets").jsonArray.single().jsonObject
        val missed = JsonObject(base + ("samples" to buildJsonArray {
            for (x in listOf(10000, 10001)) add(buildJsonObject { putJsonArray("point") { add(x); add(10000) } })
        }))
        val empty = JsonObject(base + ("targets" to buildJsonArray { add(JsonObject(originalTarget + ("vertices" to JsonArray(emptyList())))) }))
        rebuilds = 0
        WorkspaceOperations(Host(runtime)).use { operations ->
            listOf(missed, empty, JsonObject(base + ("strength" to JsonPrimitive(0)))).forEachIndexed { index, stroke ->
                val result = operations.call(operation.operation, input(before, WorkspaceDocumentOperation(operation.operation,
                    buildJsonObject { put("stroke", stroke) }), "no-change-$index"))
                assertFalse(result.getValue("applied").jsonPrimitive.boolean)
                assertEquals(before, runtime.capture()); assertEquals(history, runtime.history()); assertEquals(0, rebuilds)
            }
        }
    }

    @Test fun strictActionUnionRejectsDenseGeometryWrongModifiersAndInvalidSelectionBeforeHistory() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val operation = stroke(before.model.rig.puppet)
        WorkspaceOperations(Host(runtime)).use { operations ->
            val base = operation.request.getValue("stroke").jsonObject
            val invalid = listOf(JsonObject(base + ("points" to JsonArray(emptyList()))), JsonObject(base + ("shrink" to JsonPrimitive(true))),
                JsonObject(base + ("action" to JsonPrimitive("smooth"))), JsonObject(base + ("mode" to JsonPrimitive("simulate"))),
                JsonObject(base + ("targets" to buildJsonArray { add(buildJsonObject { put("target", "mesh:${before.model.rig.puppet.drawables.first().id.raw}")
                    putJsonObject("key") { put("Shape", 1) }; putJsonArray("vertices") { add(100000) } }) })))
            invalid.forEachIndexed { index, stroke ->
                assertFailsWith<IllegalArgumentException> { operations.call(operation.operation, input(before,
                    WorkspaceDocumentOperation(operation.operation, buildJsonObject { put("stroke", stroke) }), "invalid-$index")) }
                assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            }
        }
    }

    @Test fun committedBatchRetainsExactStrokeResultAfterLateCancellationOrHostRefreshFailure() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) {
            val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val runtime = fixture()
            val before = runtime.capture(); val operation = stroke(before.model.rig.puppet)
            WorkspaceOperations(Host(runtime) {
                withContext(NonCancellable) { committed.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed after the stroke CAS")
            }).use { operations ->
                val request = buildJsonObject { put("state", before.state); put("project_id", before.projectId); put("request_id", "stroke-batch")
                    putJsonArray("edits") { add(buildJsonObject { put("operation", operation.operation); put("request", operation.request) }) } }
                val job = operations.call("workspace_apply_edits", request); withTimeout(5000) { committed.await() }
                if (cancel) operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "late-cancel") })
                release.complete(Unit)
                val terminal = operations.wait(job); assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                val result = terminal.getValue("result").jsonObject
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(runtime.capture().historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(1, result.getValue("edit_count").jsonPrimitive.int)
                assertEquals(job, operations.call("workspace_apply_edits", request)); assertEquals(terminal, operations.wait(job))
                assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
            }
        }
    }
}
