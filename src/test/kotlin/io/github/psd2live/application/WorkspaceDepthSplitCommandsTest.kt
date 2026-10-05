package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceDepthSplitCommandsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val layers = listOf("collar", "neck", "other").mapIndexed { index, id ->
            val bounds = listOf(LayerBounds(18, 50, 50, 28), LayerBounds(35, 32, 26, 32), LayerBounds(2, 2, 12, 12))[index]
            WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 3 - index, bounds, 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(bounds.width, bounds.height,
                    ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 90 }), null, null, false)
        }
        val document = WorkspaceDocument(WorkspaceSourceArt(96, 96, layers, emptyList()), emptyMap(), emptySet(),
            layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) }, emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, meshSpacing = 12, exportMoc3 = false, generatePhysics = false)))
        return WorkspaceRuntime(rebuild).also { it.install(it.state.value.state, "depth", document, builder.build(document)) }
    }
    private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }
    private fun operation(runtime: WorkspaceRuntime<RigPreviewModel>) = WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
        val model = runtime.capture().model
        put("source_id", mesh(model, "collar").id.raw)
        put("middle_ids", JsonArray(listOf(mesh(model, "neck").id.raw).map(::JsonPrimitive)))
        put("front_layer_id", "depth-front"); put("front_mesh_id", "DepthFront"); put("glue_id", "DepthWeld")
        put("names", JsonArray(listOf("Rear", "Front").map(::JsonPrimitive)))
    })
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, edit: WorkspaceDocumentOperation) = JsonObject(edit.request + buildJsonObject {
        val before = runtime.capture(); put("project_id", before.projectId); put("state", before.state); put("request_id", "depth")
    })
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun splitDepth(state: String, request: JsonObject): WorkspaceMutationResult {
            val result = WorkspacePartitionCommands(runtime).execute(runtime.capture().projectId, state,
                listOf(WorkspaceDocumentOperation("source_split_depth", request)), "Depth split", MutationAuthor.AGENT)
            after(); return result.mutation
        }
    }
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data

    @Test fun authoredSourceMotionPathsAndDirectionalGlueSurvivePureCandidateReplayAndHistory() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val source = mesh(root.model, "collar"); val other = mesh(root.model, "other")
        val commands = WorkspaceDocumentCommands(runtime)
        val positions = source.mesh!!.positions
        val edits = listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "DepthAxis"); put("name", "Depth axis"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "DepthParent"); put("name", "Parent"); put("rows", 2); put("columns", 2); put("meshes", JsonArray(listOf(JsonPrimitive(source.id.raw)))) }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "OldWeld"); put("mesh_a", source.id.raw); put("mesh_b", other.id.raw); put("distance", 128) }),
            WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "OldPath"); put("target", "mesh:${source.id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(positions[0]); add(positions[1]) }); add(buildJsonArray { add(positions[positions.size - 2]); add(positions.last()) })
            } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { for (value in listOf(-1, 1)) add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${source.id.raw}"); putJsonObject("key") { put("DepthAxis", value) }; putJsonObject("channels") { put("opacity", if (value < 0) 0.3 else 0.7) }
            }) } }))
        val authored = commands.execute(root.projectId, root.state, "Author source", edits, MutationAuthor.USER).capture
        assertTrue(WorkspacePartitionEdits.wouldDiscardEdits(authored.model, "collar"))
        val pixels = authored.document.source.layers.first().raster.rgba.copyOf()
        val result = WorkspacePartitionCommands(runtime).execute(authored.projectId, authored.state, listOf(operation(runtime)), "Depth slices", MutationAuthor.USER)
        assertEquals(listOf("depth-front"), result.mutation.affectedLayerIds)
        assertEquals(listOf("layer:depth-front", "mesh:DepthFront", "glue:DepthWeld").toSet(),
            WorkspaceDocumentCommands.mutationResult(authored, result.commit, "Depth slices", listOf(operation(runtime))).affectedObjectIds
                .filter { it in setOf("layer:depth-front", "mesh:DepthFront", "glue:DepthWeld") }.toSet())
        assertEquals(3, runtime.history().selections.size)
        assertContentEquals(pixels, result.commit.capture.document.source.layers.first().raster.rgba)
        assertNotSame(authored.document.source.layers.first().raster.rgba, result.commit.capture.document.source.layers.last().raster.rgba)
        assertContentEquals(pixels, result.commit.capture.document.source.layers.last().raster.rgba)
        val replayed = builder.build(result.commit.capture.document)
        assertPartitionDeformers(authored.model.baseRig.puppet.deformers, result.commit.capture.model.baseRig.puppet.deformers)
        for (model in listOf(result.commit.capture.model, replayed)) {
            val back = mesh(model, "collar"); val front = mesh(model, "depth-front"); val middle = mesh(model, "neck")
            assertEquals("DepthFront", front.id.raw); assertEquals(back.parentDeformerId, front.parentDeformerId)
            assertTrue(back.drawOrder < middle.drawOrder && middle.drawOrder < front.drawOrder)
            val weld = model.rig.puppet.glues.single { it.id == "DepthWeld" }
            assertTrue(weld.pairs.all { it.indexA == it.indexB && it.weightA == 0f && it.weightB == 1f })
            assertEquals(back.mesh!!.vertexCount, weld.pairs.size)
            assertEquals("DepthWeld/OldPath", model.rig.puppet.deformPaths.single { it.drawableId == front.id }.id)
            val evaluator = CpuDeformationEvaluator()
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(ParameterId("DepthAxis") to value, StandardParameters.ANGLE_X to value * 20)
                val before = evaluator.evaluate(authored.model.rig.puppet, pose); val after = evaluator.evaluate(model.rig.puppet, pose)
                before.worldPositions.forEach { (id, vertices) -> vertices.indices.forEach { assertEquals(vertices[it], after.worldPositions.getValue(id)[it], 0.0001f) } }
                assertContentEquals(after.worldPositions.getValue(back.id), after.worldPositions.getValue(front.id))
                assertEquals(after.opacity.getValue(back.id), after.opacity.getValue(front.id))
            }
        }
        runtime.checkout(root.projectId, runtime.capture().state, root.historyHead)
        assertEquals(3, runtime.capture().model.rig.puppet.drawables.size)
        runtime.checkout(root.projectId, runtime.capture().state, result.mutation.historyNodeId)
        assertEquals("DepthFront", mesh(runtime.capture().model, "depth-front").id.raw)
        assertEquals(root.document, runtime.history().selections.first().snapshot)
    }

    @Test fun batchCanPaintNewFrontWithoutMovingRearAndLateFailurePublishesNoPrefix() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val split = operation(runtime)
        val edits = listOf(split, WorkspaceDocumentOperation("source_paint_clear", buildJsonObject { put("layer_id", "depth-front"); put("rebuild_mesh", true) }))
        val commands = WorkspaceDocumentCommands(runtime)
        val failure = assertFailsWith<WorkspaceBatchEditException> { commands.execute(before.projectId, before.state, "Bad slices", edits +
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "missing"); put("reset", true) }), MutationAuthor.AGENT) }
        assertEquals(2, failure.index); assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
        val result = commands.execute(before.projectId, before.state, "Split and erase", edits, MutationAuthor.AGENT)
        assertEquals(2, runtime.history().selections.size)
        val front = result.capture.document.source.layers.single { it.id.raw == "depth-front" }
        assertTrue(front.raster.rgba.indices.filter { it % 4 == 3 }.all { front.raster.rgba[it] == 0.toByte() })
        assertContentEquals(before.document.source.layers.first().raster.rgba, result.capture.document.source.layers.first().raster.rgba)
        assertContentEquals(mesh(before.model, "collar").mesh!!.positions, mesh(result.capture.model, "collar").mesh!!.positions)
        val replayed = builder.build(result.capture.document)
        assertContentEquals(mesh(result.capture.model, "depth-front").mesh!!.positions, mesh(replayed, "depth-front").mesh!!.positions)
        assertDepthGlues(result.capture.model.rig.puppet.glues, replayed.rig.puppet.glues)
    }

    @Test fun deletedSourcePixelsGenerationInputsAndMeshOverridesAreRetainedByDepthCandidate() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        val document = root.document.copy(deletedLayerIds = setOf("other"), generationSource = root.document.source,
            meshSource = root.document.source, meshOverrides = mapOf("collar" to MeshSettings(outerMargin = 3f)))
        runtime.execute(root.projectId, root.state, "Inputs", MutationAuthor.USER, listOf(WorkspaceDocumentEdit { _, _ -> document }))
        val before = runtime.capture()
        val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(operation(runtime)), "Copy", MutationAuthor.USER).commit.capture
        assertEquals(listOf("collar", "neck", "other", "depth-front"), result.document.source.layers.map { it.id.raw })
        assertEquals(before.document.deletedLayerIds, result.document.deletedLayerIds)
        assertEquals(before.document.generationSource, result.document.generationSource); assertEquals(before.document.meshSource, result.document.meshSource)
        assertEquals(before.document.meshOverrides.getValue("collar"), result.document.meshOverrides.getValue("depth-front"))
        assertContentEquals(mesh(before.model, "collar").mesh!!.positions, mesh(result.model, "collar").mesh!!.positions)
    }

    @Test fun mouthOwnerAndDerivedLipDepthCopiesCreateOnlyTheSelectedMeshAndRetainMouthMotion() = runBlocking<Unit> {
        for (sourceLayerId in listOf("collar", MouthLipLayer.idFor("collar", 0))) {
            val runtime = fixture(); val root = runtime.capture()
            val document = root.document.copy(layerOverrides = root.document.layerOverrides +
                ("collar" to LayerClassificationOverride(tag = SemanticTag.MOUTH)))
            runtime.execute(root.projectId, root.state, "Mouth", MutationAuthor.USER,
                listOf(WorkspaceDocumentEdit { _, _ -> document }))
            val before = runtime.capture()
            assertTrue(before.model.analysis.layers.any { it.source is MouthLipLayer })
            val source = mesh(before.model, sourceLayerId)
            val edit = operation(runtime).copy(request = JsonObject(operation(runtime).request +
                ("source_id" to JsonPrimitive(source.id.raw))))
            val committed = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state,
                listOf(edit), "Mouth slices", MutationAuthor.USER).commit.capture
            for (model in listOf(committed.model, builder.build(committed.document))) {
                assertEquals(before.model.rig.puppet.drawables.map { it.id }.toSet() + DrawableId("DepthFront"),
                    model.rig.puppet.drawables.map { it.id }.toSet())
                assertEquals(before.model.analysis.layers.count { it.source is MouthLipLayer },
                    model.analysis.layers.count { it.source is MouthLipLayer })
                val evaluator = CpuDeformationEvaluator()
                for (open in listOf(0f, 0.5f, 1f)) {
                    val pose = mapOf(StandardParameters.MOUTH_OPEN to open)
                    val original = evaluator.evaluate(before.model.rig.puppet, pose)
                    val actual = evaluator.evaluate(model.rig.puppet, pose)
                    original.worldPositions.forEach { (id, positions) ->
                        positions.indices.forEach { assertEquals(positions[it], actual.worldPositions.getValue(id)[it], 0.0001f) }
                    }
                    assertContentEquals(actual.worldPositions.getValue(source.id), actual.worldPositions.getValue(DrawableId("DepthFront")))
                }
            }
            WorkspaceDocumentCommands(runtime).execute(committed.projectId, committed.state, "Erase front",
                listOf(WorkspaceDocumentOperation("source_paint_clear", buildJsonObject {
                    put("layer_id", "depth-front"); put("rebuild_mesh", true)
                })), MutationAuthor.USER)
            val erased = builder.build(runtime.capture().document)
            assertEquals(committed.model.rig.puppet.drawables.map { it.id }.toSet(), erased.rig.puppet.drawables.map { it.id }.toSet())
            assertContentEquals(source.mesh!!.positions, mesh(erased, sourceLayerId).mesh!!.positions)
        }
    }

    @Test fun staleInvalidTargetsDuplicateIdsAndRejectedProjectionLeaveStateAndHistoryUntouched() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val edit = operation(runtime); val commands = WorkspacePartitionCommands(runtime)
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", listOf(WorkspaceDocumentOperation("source_split_depth", buildJsonObject {})), "Stale", MutationAuthor.USER) }
        for (fields in listOf(buildJsonObject { put("source_id", "missing") }, buildJsonObject { put("front_layer_id", "other") },
            buildJsonObject { put("front_mesh_id", mesh(before.model, "collar").id.raw) }, buildJsonObject { put("glue_id", "") },
            buildJsonObject { put("middle_ids", JsonArray(listOf(JsonPrimitive(mesh(before.model, "collar").id.raw)))) },
            buildJsonObject { put("names", JsonArray(listOf("Same", " Same ").map(::JsonPrimitive))) })) {
            assertFailsWith<IllegalArgumentException> { commands.execute(before.projectId, before.state, listOf(edit.copy(request = JsonObject(edit.request + fields))), "Invalid", MutationAuthor.USER) }
            assertEquals(before, runtime.capture())
        }
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, listOf(edit), "Projection", MutationAuthor.USER) { _, _, _ -> error("Rejected") } }
        assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
    }

    @Test fun rasterCopyChecksOriginalCoroutineCancellationBeforePublishing() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var calls = 0
        val work = object : WorkspaceRasterWork {
            override fun progress(fraction: Float, message: String) {}
            override fun checkpoint() { if (++calls == 4) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) } }
        }
        val pending = async(Dispatchers.Default) { WorkspacePartitionCommands(runtime, work).execute(before.projectId, before.state, listOf(operation(runtime)), "Copy", MutationAuthor.USER) }
        try {
            withTimeout(10000) { entered.await() }; pending.cancel(); release.countDown()
            assertFailsWith<CancellationException> { pending.await() }; pending.join(); assertEquals(before, runtime.capture())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun publicDepthTaskCancellationAndConcurrentCommitDuringRebuildPreserveOriginalCandidate() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var block = false
            val runtime = fixture { document -> if (block) { entered.complete(Unit); release.await() }; builder.build(document) }
            val before = runtime.capture(); block = true
            WorkspaceOperations(Host(runtime)).use { operations ->
                val request = input(runtime, operation(runtime)); val job = operations.registry.invoke("source_split_depth", request, agent).data
                withTimeout(10000) { entered.await() }
                val expected = if (cancel) {
                    operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent); before
                } else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit); val terminal = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", terminal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(1, runtime.history().selections.size)
                assertEquals(job.getValue("id"), operations.registry.invoke("source_split_depth", request, agent).data.getValue("id"))
            }
        }
    }

    @Test fun lateCancellationOrRefreshFailureKeepsCommittedTaskResultAndStableFrontIdentity() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val runtime = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime) {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }; if (!cancel) throw java.io.IOException("Refresh failed")
            }).use { operations ->
                val definition = operations.registry.definition("source_split_depth"); assertTrue(definition.jobBacked && definition.batchable)
                val request = input(runtime, operation(runtime)); val job = operations.registry.invoke("source_split_depth", request, agent).data
                withTimeout(10000) { entered.await() }
                if (cancel) operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent)
                release.complete(Unit); val terminal = operations.wait(job)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(listOf("depth-front"), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
                assertEquals("DepthFront", mesh(runtime.capture().model, "depth-front").id.raw)
                assertEquals(job.getValue("id"), operations.registry.invoke("source_split_depth", request, agent).data.getValue("id")); assertEquals(terminal, operations.wait(job))
            }
        }
    }
}

internal fun assertDepthGlues(expected: List<Glue>, actual: List<Glue>, checkIds: Boolean = true) {
    if (checkIds) assertEquals(expected.map { it.id }, actual.map { it.id })
    expected.zip(actual).forEach { (first, second) ->
        assertEquals(first.meshA, second.meshA); assertEquals(first.meshB, second.meshB)
        assertEquals(first.pairs.map { listOf(it.indexA, it.indexB, it.weightA, it.weightB) },
            second.pairs.map { listOf(it.indexA, it.indexB, it.weightA, it.weightB) })
    }
}
