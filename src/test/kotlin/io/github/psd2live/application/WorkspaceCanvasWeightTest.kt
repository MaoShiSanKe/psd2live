package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCanvasWeightTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author)
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        fun layer(id: String, x: Int) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 1,
            LayerBounds(x, 4, 32, 32), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 3) 255.toByte() else 100 }), null, null, false)
        val document = WorkspaceDocument(WorkspaceSourceArt(72, 48, listOf(layer("a", 2), layer("b", 2)), emptyList()),
            emptyMap(), emptySet(), mapOf("a" to LayerClassificationOverride(tag = SemanticTag.OBJECTS),
                "b" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)), emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, meshOnly = true, exportMoc3 = false, generatePhysics = false)))
        return WorkspaceRuntime(rebuild).also { it.install(it.state.value.state, "weights", document, builder.build(document)) }
    }
    private fun targets(model: RigPreviewModel) = listOf("a", "b").map { layer -> model.rig.layerIdByDrawableId.entries.single { it.value == layer }.key }
    private fun paint(ids: List<String>, action: String = "brush", mode: String = "set") = WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject { put("edit", buildJsonObject {
        put("action", action); put("kind", "pin"); put("targets", JsonArray(ids.map(::JsonPrimitive)))
        if (action != "invert") { put("mode", mode); put("strength", 0.7) }
        if (action == "brush") {
            put("radius", 100); put("hardness", 0.5); put("connected_only", false)
            putJsonArray("points") { add(buildJsonArray { add(0); add(0) }); add(buildJsonArray { add(72); add(48) }) }
        }
    }) })
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, operation: WorkspaceDocumentOperation, id: String) = JsonObject(operation.request + buildJsonObject {
        put("state", runtime.capture().state); put("project_id", runtime.capture().projectId); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })

    @Test fun publicPaintAndOrderedInvertUseTheSameMaterializationAndSurviveHistoryArchiveReplay() = runBlocking {
        val runtime = fixture(); val before = runtime.capture(); val ids = targets(before.model)
        val operation = paint(ids)
        val expected = RigAuthoringJournal.compile(before.model.rig.puppet, WorkspaceCanvasWeightEdits.commands(before.model.rig.puppet, operation)).first
        WorkspaceOperations(Host(runtime)).use { operations ->
            assertTrue(operations.registry.definition("vertex_group_paint").batchable)
            val request = input(runtime, operation, "paint")
            val result = operations.call(operation.operation, request)
            assertEquals(result, operations.call(operation.operation, request))
            val painted = runtime.capture()
            assertEquals(2, painted.model.rig.puppet.vertexGroups.size)
            expected.vertexGroups.zip(painted.model.rig.puppet.vertexGroups).forEach { (a, b) -> assertContentEquals(a.weights, b.weights) }
            val invert = paint(ids, "invert")
            val job = operations.call("workspace_apply_edits", buildJsonObject {
                put("project_id", painted.projectId); put("state", painted.state); put("request_id", "invert")
                putJsonArray("edits") { repeat(2) { add(buildJsonObject { put("operation", invert.operation); put("request", invert.request) }) } }
            })
            assertEquals("completed", operations.wait(job).getValue("status").jsonPrimitive.content)
            val after = runtime.capture()
            painted.model.rig.puppet.vertexGroups.zip(after.model.rig.puppet.vertexGroups).forEach { (a, b) ->
                a.weights.indices.forEach { assertEquals(a.weights[it], b.weights[it], 0.00001f) }
            }
            val reopenedPath = temporary.resolve("weights.psd2live")
            ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store"))), reopenedPath)
            val reopened = ProjectRepository().open(reopenedPath).use { builder.build(it.history.head().snapshot) }
            after.model.rig.puppet.vertexGroups.zip(reopened.rig.puppet.vertexGroups).forEach { (a, b) -> assertContentEquals(a.weights, b.weights) }
            val replayed = builder.build(after.document)
            after.model.rig.puppet.vertexGroups.zip(replayed.rig.puppet.vertexGroups).forEach { (a, b) -> assertContentEquals(a.weights, b.weights) }
            runtime.checkout(after.projectId, after.state, before.historyHead)
            assertTrue(runtime.capture().model.rig.puppet.vertexGroups.isEmpty())
        }
    }

    @Test fun glueWeightEditsRetainBothAuthoredRecordsAndRejectBadInputBeforePublishing() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val ids = targets(root.model)
        val commands = WorkspaceDocumentCommands(runtime)
        val authored = commands.execute(root.projectId, root.state, "Animated seam", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            *listOf("seam-one", "seam-two").mapIndexed { index, id -> WorkspaceDocumentOperation("canvas_glue_edit", buildJsonObject {
                put("action", "brush"); put("id", id); put("mesh_a", ids[0]); put("mesh_b", ids[1]); put("distance", 100)
                putJsonArray("hits_a") { add(index) }; putJsonArray("hits_b") { add(index) }
            }) }.toTypedArray(),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject { put("op", "set"); put("target", "glue:seam-one"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("channels") { put("glueIntensity", 0.3) } })
            } })
        ), MutationAuthor.USER).capture
        // One record carries an animated intensity; a weight dab must retain those identities.
        val source = authored.model.rig.puppet
        assertEquals(2, source.glues.size)
        val hit = source.glues.first().pairs.first().indexA
        val operation = WorkspaceDocumentOperation("canvas_glue_edit", buildJsonObject {
            put("action", "weights"); put("mesh_a", ids[0]); put("mesh_b", ids[1]); put("weight_mode", "a"); put("delta", -0.2)
            putJsonArray("hits_a") { add(hit) }
        })
        WorkspaceOperations(Host(runtime)).use { operations ->
            val painted = operations.call(operation.operation, input(runtime, operation, "weld"))
            assertEquals(runtime.capture().state, painted.getValue("state").jsonPrimitive.content)
            val after = runtime.capture(); val next = after.model.rig.puppet
            source.glues.zip(next.glues).forEach { (old, fresh) ->
                assertEquals(old.id, fresh.id); assertEquals(old.intensity, fresh.intensity)
                assertEquals(old.channelGrids.gridsByChannel.keys, fresh.channelGrids.gridsByChannel.keys)
                old.channelGrids.gridsByChannel.forEach { (channel, grid) ->
                    val nextGrid = fresh.channelGrids.gridsByChannel.getValue(channel)
                    assertEquals(grid.axes.size, nextGrid.axes.size)
                    grid.axes.zip(nextGrid.axes).forEach { (a, b) ->
                        assertEquals(a.parameterId, b.parameterId); assertContentEquals(a.keys, b.keys)
                    }
                    assertEquals(grid.cells.size, nextGrid.cells.size)
                    grid.cells.zip(nextGrid.cells).forEach { (a, b) ->
                        assertContentEquals(a.coordinate, b.coordinate); assertEquals(a.form, b.form)
                    }
                }
                old.pairs.zip(fresh.pairs).forEach { (a, b) ->
                    assertEquals(if (a.indexA == hit) (a.weightA - 0.2f).coerceAtLeast(0f) else a.weightA, b.weightA, 0.00001f)
                    assertEquals(a.weightB, b.weightB)
                }
            }
            val history = runtime.history()
            assertFailsWith<IllegalArgumentException> {
                commands.execute(after.projectId, after.state, "Unavailable Glue blend binding", listOf(
                    WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                        put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape")
                    }), WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                        add(buildJsonObject { put("op", "set"); put("target", "glue:seam-one"); putJsonObject("key") { put("Blend", 1) }
                            putJsonObject("channels") { put("glueIntensity", 0.2) } })
                    } })
                ), MutationAuthor.AGENT)
            }
            assertEquals(after, runtime.capture()); assertEquals(history, runtime.history())
            for ((n, invalid) in listOf(JsonObject(operation.request + ("weight_mode" to JsonPrimitive("wrong"))),
                JsonObject(operation.request + ("hits_a" to JsonArray(listOf(JsonPrimitive(Int.MAX_VALUE))))),
                JsonObject(operation.request + ("pose" to buildJsonObject { put("Missing", 1) }))).withIndex()) {
                assertFailsWith<IllegalArgumentException> { operations.call(operation.operation, input(runtime, operation.copy(request = invalid), "bad$n")) }
                assertEquals(after, runtime.capture()); assertEquals(history, runtime.history())
            }
            val replayed = builder.build(after.document).rig.puppet
            fun assertGlueStructure(candidate: PuppetModel) {
                assertEquals(next.glues.size, candidate.glues.size)
                next.glues.zip(candidate.glues).forEach { (a, b) ->
                    assertEquals(a.id, b.id); assertEquals(a.meshA, b.meshA); assertEquals(a.meshB, b.meshB)
                    assertEquals(a.intensity, b.intensity); assertEquals(a.pairs.size, b.pairs.size)
                    a.pairs.zip(b.pairs).forEach { (x, y) ->
                        assertEquals(x.indexA, y.indexA); assertEquals(x.indexB, y.indexB)
                        assertEquals(x.weightA, y.weightA); assertEquals(x.weightB, y.weightB)
                    }
                    assertEquals(a.channelGrids.gridsByChannel.keys, b.channelGrids.gridsByChannel.keys)
                    a.channelGrids.gridsByChannel.forEach { (channel, grid) ->
                        val other = b.channelGrids.gridsByChannel.getValue(channel)
                        assertEquals(grid.axes.size, other.axes.size)
                        grid.axes.zip(other.axes).forEach { (x, y) ->
                            assertEquals(x.parameterId, y.parameterId); assertContentEquals(x.keys, y.keys)
                            assertEquals(next.parameters.single { it.id == x.parameterId }.kind,
                                candidate.parameters.single { it.id == y.parameterId }.kind)
                        }
                        assertEquals(grid.cells.size, other.cells.size)
                        grid.cells.zip(other.cells).forEach { (x, y) ->
                            assertContentEquals(x.coordinate, y.coordinate); assertEquals(x.form, y.form)
                        }
                    }
                }
                assertEquals(ParameterKind.NORMAL, candidate.parameters.single { it.id.raw == "Shape" }.kind)
            }
            assertGlueStructure(replayed)
            val archive = temporary.resolve("glue.psd2live")
            ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("glue-store"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertGlueStructure(reopened.rig.puppet)
            val files = PSD2LivePipeline().run(after.document.source, "weights", temporary.resolve("export"),
                after.document.config().copy(exportMoc3 = true)).exportedFiles
            val cmo3 = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            val moc3 = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null)
            for (candidate in listOf(replayed, reopened.rig.puppet, cmo3, moc3)) for (shape in listOf(-1f, 0f, 0.4f, 1f)) {
                val a = CpuDeformationEvaluator().evaluate(next, mapOf(ParameterId("Shape") to shape))
                val b = CpuDeformationEvaluator().evaluate(candidate, mapOf(ParameterId("Shape") to shape))
                assertEquals(a.worldPositions.keys, b.worldPositions.keys)
                a.worldPositions.forEach { (id, xy) ->
                    val actual = b.worldPositions.getValue(id)
                    assertEquals(xy.size, actual.size)
                    xy.indices.forEach { i -> assertEquals(xy[i], actual[i], 0.001f) }
                    assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), 0.00001f)
                }
            }
            fun render(preview: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(preview, "glue", mapOf("Shape" to 0.4f),
                preview.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 72f, 48f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            val png = render(after.model); assertContentEquals(png, render(reopened))
            val image = javax.imageio.ImageIO.read(png.inputStream())
            assertTrue((0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) ushr 24 != 0 } } > 500)
            val visuals = Path.of("build/canvas-weight-visual")
            Files.createDirectories(visuals); Files.write(visuals.resolve("glue.png"), png)
            Files.write(visuals.resolve("glue-reopened.png"), render(reopened))
        }
    }

    @Test fun lateInvalidBatchAndCancellationPublishNoPrefixAndStaleSingleFails() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var block = false
        val runtime = fixture { document ->
            if (block && document.rigEdits.authoringJournal.any { it["op"] == JsonPrimitive(VertexGroupJournal.PUT) }) {
                entered.complete(Unit); awaitCancellation()
            }
            builder.build(document)
        }
        val before = runtime.capture(); val history = runtime.history(); val ids = targets(before.model)
        WorkspaceOperations(Host(runtime)).use { operations ->
            suspend fun batch(key: String, edits: List<WorkspaceDocumentOperation>) = operations.call("workspace_apply_edits", buildJsonObject {
                put("state", before.state); put("project_id", before.projectId); put("request_id", key)
                put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } }))
            })
            val invalid = WorkspaceDocumentOperation("canvas_glue_edit", buildJsonObject {
                put("action", "weights"); put("mesh_a", ids[0]); put("mesh_b", "missing"); putJsonArray("hits_a") { add(0) }
            })
            val failed = operations.wait(batch("invalid", listOf(paint(ids), invalid)))
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertEquals(1, failed.getValue("error").jsonObject.getValue("edit_index").jsonPrimitive.int)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            block = true
            val job = batch("cancel", listOf(paint(ids)))
            withTimeout(5000) { entered.await() }
            operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "stop") })
            assertEquals("cancelled", operations.wait(job).getValue("status").jsonPrimitive.content)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            block = false
            val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
            assertFailsWith<WorkspaceConflict> { operations.call("vertex_group_paint", JsonObject(paint(ids).request + buildJsonObject {
                put("project_id", before.projectId); put("state", before.state); put("request_id", "stale")
            })) }
            assertEquals(foreign, runtime.capture())
        }
    }
}
