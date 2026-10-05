package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.edit.withDrawablesDeleted
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

class WorkspaceAuthoredLayerDeletionTest {
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(deleted: Set<String> = emptySet(),
                                rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val layers = listOf("first", "second", "third").mapIndexed { index, id ->
            val bounds = listOf(LayerBounds(5, 5, 30, 35), LayerBounds(35, 30, 20, 40), LayerBounds(2, 72, 15, 15))[index]
            WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, index, bounds, 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(bounds.width, bounds.height,
                    ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 90 }), null, null, false)
        }
        val document = WorkspaceDocument(WorkspaceSourceArt(64, 96, layers, emptyList()), emptyMap(), deleted,
            layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) }, emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, meshSpacing = 12, exportMoc3 = false, generatePhysics = false)))
        return WorkspaceRuntime<RigPreviewModel>(rebuild = rebuild).also {
            it.install(it.state.value.state, "authored-layers", document, builder.build(document))
        }
    }
    private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }
    private fun deletion(id: String) = WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", id) })
    private fun restoration(ids: List<String>? = null) = WorkspaceDocumentOperation("layer_restore", buildJsonObject {
        ids?.let { put("layer_ids", JsonArray(it.map(::JsonPrimitive))) }
    })
    private suspend fun author(runtime: WorkspaceRuntime<RigPreviewModel>, secondLayer: String = "second"): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture(); val first = mesh(before.model, "first"); val second = mesh(before.model, secondLayer)
        return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Author original layers", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "LayerAxis"); put("name", "Layer axis"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                put("id", "LayerParent"); put("name", "Authored parent"); put("rows", 2); put("columns", 2)
                put("meshes", JsonArray(listOf(first.id.raw, second.id.raw).map(::JsonPrimitive)))
            }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "LayerWeld"); put("mesh_a", first.id.raw); put("mesh_b", second.id.raw); put("distance", 128) }),
            WorkspaceDocumentOperation("path_put", buildJsonObject {
                put("id", "LayerPath"); put("target", "mesh:" + first.id.raw)
                put("points", JsonArray(first.mesh!!.positions.asList().chunked(2).take(2).map { JsonArray(it.map(::JsonPrimitive)) }))
            }),
            WorkspaceDocumentOperation("vertex_group_update", buildJsonObject {
                put("target", "mesh:" + first.id.raw); put("name", "Pin"); put("kind", "pin"); put("rule", "fill"); put("value", 0.5)
            }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { for (value in listOf(-1, 1)) add(buildJsonObject {
                put("op", "set"); put("target", "mesh:" + first.id.raw); putJsonObject("key") { put("LayerAxis", value) }
                putJsonObject("channels") { put("opacity", if (value < 0) 0.3 else 0.7) }
            }) } }),
            WorkspaceDocumentOperation("rig_edit_structure", buildJsonObject { putJsonArray("edits") { add(buildJsonObject {
                put("action", "static"); put("kind", "mesh"); put("id", second.id.raw); put("masked_by", JsonArray(listOf(JsonPrimitive(first.id.raw))))
            }) } }),
        ), MutationAuthor.USER).capture
    }
    private fun assertEvaluation(expected: PuppetModel, actual: PuppetModel) {
        assertEquals(expected.drawables.map { it.id }.toSet(), actual.drawables.map { it.id }.toSet())
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("LayerAxis") to value, StandardParameters.ANGLE_X to value * 20)
            val original = evaluator.evaluate(expected, pose); val after = evaluator.evaluate(actual, pose)
            original.worldPositions.forEach { (id, points) ->
                points.indices.forEach { assertEquals(points[it], after.worldPositions.getValue(id)[it], 0.0001f) }
                assertEquals(original.opacity.getValue(id), after.opacity.getValue(id))
            }
        }
    }

    @Test fun ordinaryAuthoredLayerDeletionReplaysBeforeFilteringAndRestoresAllBindings() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        assertTrue(authored.document.rigEdits.authoringJournal.none { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
        val commands = WorkspaceLayerCommands(runtime); val first = mesh(authored.model, "first")
        val deleted = commands.execute(authored.projectId, authored.state, deletion("first"), "Delete", MutationAuthor.USER).commit.capture
        assertSame(authored.document.source, deleted.document.source)
        for (model in listOf(deleted.model, builder.build(deleted.document))) {
            assertEvaluation(authored.model.rig.puppet.withDrawablesDeleted(setOf(first.id)), model.rig.puppet)
            assertTrue(model.rig.puppet.glues.isEmpty()); assertTrue(model.rig.puppet.deformPaths.isEmpty()); assertTrue(model.rig.puppet.vertexGroups.isEmpty())
            assertEquals(authored.model.rig.puppet.deformers.map { it.id }, model.rig.puppet.deformers.map { it.id })
        }
        val noop = commands.execute(deleted.projectId, deleted.state, deletion("first"), "Again", MutationAuthor.USER)
        assertFalse(noop.commit.applied); assertEquals(deleted.state, noop.mutation.state)
        val restored = commands.execute(deleted.projectId, deleted.state, restoration(), "Restore", MutationAuthor.USER).commit.capture
        for (model in listOf(restored.model, builder.build(restored.document))) {
            assertEvaluation(authored.model.rig.puppet, model.rig.puppet)
            assertEquals(authored.model.rig.puppet.deformPaths, model.rig.puppet.deformPaths)
            assertEquals(authored.model.rig.puppet.vertexGroups, model.rig.puppet.vertexGroups)
            assertDepthGlues(authored.model.rig.puppet.glues, model.rig.puppet.glues)
            assertEquals(authored.model.rig.puppet.drawables.map { it.id to it.maskedBy }, model.rig.puppet.drawables.map { it.id to it.maskedBy })
        }
        val all = WorkspaceDocumentCommands(runtime).execute(restored.projectId, restored.state, "Delete all",
            listOf("first", "second", "third").map(::deletion), MutationAuthor.USER).capture
        assertTrue(all.model.rig.puppet.drawables.isEmpty())
        val back = commands.execute(all.projectId, all.state, restoration(), "Restore all", MutationAuthor.USER).commit.capture
        assertEvaluation(authored.model.rig.puppet, back.model.rig.puppet)
        assertEquals(authored.document, runtime.history().selections.single { it.node.id == authored.historyHead }.snapshot)
        runtime.checkout(back.projectId, back.state, authored.historyHead)
        assertEquals(authored.revision, runtime.capture().revision)
    }

    @Test fun depthRearOrFrontDeletionPreservesTheOtherSliceMotionAndRestoresTheWeld() = runBlocking<Unit> {
        for (layer in listOf("first", "front")) {
            val runtime = fixture(); val authored = author(runtime)
            val depth = WorkspacePartitionCommands(runtime).execute(authored.projectId, authored.state,
                listOf(WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
                    put("source_id", mesh(authored.model, "first").id.raw); put("middle_ids", JsonArray(listOf(JsonPrimitive(mesh(authored.model, "third").id.raw))))
                    put("front_layer_id", "front"); put("front_mesh_id", "FrontMesh"); put("glue_id", "DepthWeld")
                })), "Depth", MutationAuthor.USER).commit.capture
            val removed = mesh(depth.model, layer).id
            val deleted = WorkspaceLayerCommands(runtime).execute(depth.projectId, depth.state, deletion(layer), "Delete slice", MutationAuthor.USER).commit.capture
            for (model in listOf(deleted.model, builder.build(deleted.document))) {
                assertEvaluation(depth.model.rig.puppet.withDrawablesDeleted(setOf(removed)), model.rig.puppet)
                assertTrue(model.rig.puppet.glues.none { it.meshA == removed || it.meshB == removed })
            }
            val restored = WorkspaceLayerCommands(runtime).execute(deleted.projectId, deleted.state, restoration(), "Restore slice", MutationAuthor.USER).commit.capture
            assertEvaluation(depth.model.rig.puppet, restored.model.rig.puppet)
            assertDepthGlues(depth.model.rig.puppet.glues, restored.model.rig.puppet.glues)
        }
    }

    @Test fun aLegacyDeletedLayerDoesNotRenumberExistingAuthoredTargetsWhenMembershipIsUpgraded() = runBlocking<Unit> {
        val runtime = fixture(setOf("third")); val original = author(runtime)
        val deleted = WorkspaceLayerCommands(runtime).execute(original.projectId, original.state, deletion("first"), "Delete", MutationAuthor.USER).commit.capture
        assertEvaluation(original.model.rig.puppet.withDrawablesDeleted(setOf(mesh(original.model, "first").id)), deleted.model.rig.puppet)
        val restored = WorkspaceLayerCommands(runtime).execute(deleted.projectId, deleted.state, restoration(listOf("first")), "Restore", MutationAuthor.USER).commit.capture
        assertEquals(setOf("third"), restored.document.deletedLayerIds)
        assertEvaluation(original.model.rig.puppet, restored.model.rig.puppet)
        assertEquals(original.document, runtime.history().selections.single { it.node.id == original.historyHead }.snapshot)
        assertEvaluation(original.model.rig.puppet, builder.build(original.document).rig.puppet)
    }

    @Test fun ordinaryHiddenMeshesParticipateInSettingsRegenerationAndPersistReboundPathsAndWeights() = runBlocking<Unit> {
        for (all in listOf(false, true)) {
            val runtime = fixture(); val authored = author(runtime); val commands = WorkspaceDocumentCommands(runtime)
            val settings = WorkspaceDocumentOperation("settings_update", buildJsonObject {
                putJsonObject("changes") { put("meshSpacing", 16); put("meshEdgeMode", "TRIPLE") }
            })
            val visible = commands.execute(authored.projectId, authored.state, "Visible mesh settings", listOf(settings), MutationAuthor.USER).capture
            val previous = runtime.checkout(visible.projectId, visible.state, authored.historyHead)
            val deleted = commands.execute(previous.projectId, previous.state, "Hide meshes",
                (if (all) listOf("first", "second", "third") else listOf("first")).map(::deletion), MutationAuthor.USER).capture
            val changed = commands.execute(deleted.projectId, deleted.state, "Hidden mesh settings", listOf(settings), MutationAuthor.USER).capture
            assertTrue(changed.model.rig.puppet.drawables.none { it.id == mesh(authored.model, "first").id })
            val restored = WorkspaceLayerCommands(runtime).execute(changed.projectId, changed.state, restoration(), "Restore", MutationAuthor.USER).commit.capture
            for (model in listOf(restored.model, builder.build(restored.document))) {
                assertEvaluation(visible.model.rig.puppet, model.rig.puppet)
                assertEquals(visible.model.rig.puppet.deformPaths, model.rig.puppet.deformPaths)
                assertEquals(visible.model.rig.puppet.vertexGroups, model.rig.puppet.vertexGroups)
                assertDepthGlues(visible.model.rig.puppet.glues, model.rig.puppet.glues)
            }
            assertNotEquals(authored.model.rig.puppet.deformPaths, restored.model.rig.puppet.deformPaths)
            assertEquals(authored.document, runtime.history().selections.single { it.node.id == authored.historyHead }.snapshot)
        }
    }

    @Test fun restoringLegacyHiddenArtPreservesVisibleIdsAndTheirAlreadyAuthoredMotion() = runBlocking<Unit> {
        val runtime = fixture(setOf("second")); val original = author(runtime, "third")
        val restored = WorkspaceLayerCommands(runtime).execute(original.projectId, original.state,
            restoration(), "Restore legacy art", MutationAuthor.USER).commit.capture
        for (model in listOf(restored.model, builder.build(restored.document))) {
            val restoredId = mesh(model, "second").id
            assertEvaluation(original.model.rig.puppet, model.rig.puppet.withDrawablesDeleted(setOf(restoredId)))
            assertEquals(mesh(original.model, "third").id, mesh(model, "third").id)
            assertEquals(original.model.rig.puppet.deformPaths, model.rig.puppet.deformPaths)
            assertEquals(original.model.rig.puppet.vertexGroups, model.rig.puppet.vertexGroups)
            assertDepthGlues(original.model.rig.puppet.glues, model.rig.puppet.glues)
        }
        assertEquals(original.document, runtime.history().selections.single { it.node.id == original.historyHead }.snapshot)
    }

    @Test fun deletingPaintedOriginalArtKeepsItsSavedGenerationFrameRatherThanItsCroppedPixels() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        val pixels = ByteArray(64 * 96 * 4)
        for (y in 20 until 40) for (x in 20 until 35) {
            val index = (y * 64 + x) * 4
            pixels[index] = 90; pixels[index + 1] = 90; pixels[index + 2] = 90; pixels[index + 3] = 255.toByte()
        }
        val painted = WorkspaceRasterCommands(runtime).commitRaster(authored.projectId, authored.state,
            WorkspacePaintRaster("first", LayerRaster(64, 96, pixels), rebuildMesh = false), "Crop painted art", MutationAuthor.USER).commit.capture
        assertNotNull(painted.document.generationSource)
        assertNotEquals(painted.document.generationSource.layers.first().bounds, painted.document.source.layers.first().bounds)
        val deleted = WorkspaceLayerCommands(runtime).execute(painted.projectId, painted.state, deletion("second"), "Delete", MutationAuthor.USER).commit.capture
        assertEvaluation(painted.model.rig.puppet.withDrawablesDeleted(setOf(mesh(painted.model, "second").id)), deleted.model.rig.puppet)
        val restored = WorkspaceLayerCommands(runtime).execute(deleted.projectId, deleted.state, restoration(), "Restore", MutationAuthor.USER).commit.capture
        for (model in listOf(restored.model, builder.build(restored.document))) assertEvaluation(painted.model.rig.puppet, model.rig.puppet)
    }

    @Test fun membershipUpgradeCancellationConflictAndProjectionFailureNeverPublishFrozenIdsOrMarkers() = runBlocking<Unit> {
        supervisorScope { for (cancel in listOf(true, false)) {
            var block = false; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = fixture(rebuild = { document ->
                if (block) { entered.complete(Unit); release.await() }
                builder.build(document)
            })
            val before = author(runtime); val history = runtime.history()
            assertTrue(before.document.rigEdits.authoringJournal.none { it["op"]?.jsonPrimitive?.content == RigLayerDeletion.OP })
            val commands = WorkspaceLayerCommands(runtime)
            assertFailsWith<WorkspaceConflict> {
                commands.execute(before.projectId, "stale", WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject {}), "Stale", MutationAuthor.USER)
            }
            assertFailsWith<IllegalStateException> {
                commands.execute(before.projectId, before.state, deletion("first"), "Projection", MutationAuthor.USER) { _, _, _ -> error("Rejected") }
            }
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            block = true
            val pending = async(Dispatchers.Default) { commands.execute(before.projectId, before.state, deletion("first"), "Delete", MutationAuthor.USER) }
            try {
                withTimeout(10000) { entered.await() }
                val expected = if (cancel) { pending.cancel(); before } else
                    runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                if (cancel) assertFailsWith<CancellationException> { pending.await() }
                else assertFailsWith<WorkspaceConflict> { pending.await() }
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
            } finally { release.complete(Unit); pending.cancelAndJoin() }
        } }
    }
}
