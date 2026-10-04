package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import kotlin.test.*

class WorkspaceLayerCommandsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime(rebuild)
        simulationFixture(runtime)
        val original = runtime.capture().document
        val blank = (WorkspaceSourceLayer.copyOf(original.source.layers.single(), 0) as WorkspaceSourceLayer).copy(
            id = LayerId("blank"), name = "Blank artwork", bounds = LayerBounds(40, 30, 1, 1), raster = LayerRaster(1, 1, ByteArray(4)))
        val document = original.copy(source = WorkspaceSourceArt(64, 96, original.source.layers + blank, emptyList()))
        runtime.install(runtime.state.value.state, "layers", document, builder.build(document))
        return runtime
    }
    private fun delete(id: String) = WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", id) })
    private fun restore(vararg ids: String) = WorkspaceDocumentOperation("layer_restore", buildJsonObject {
        if (ids.isNotEmpty()) put("layer_ids", JsonArray(ids.map(::JsonPrimitive)))
    })
    private suspend fun born(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        return WorkspaceRasterCommands(runtime).execute(before.projectId, before.state,
            WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
                put("layer_id", "blank"); put("shape", "rectangle"); put("filled", true)
                put("from", buildJsonArray { add(35); add(25) }); put("to", buildJsonArray { add(60); add(65) })
                put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
            }), "Birth", MutationAuthor.USER).commit.capture
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspaceLayerCommands(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        private suspend fun run(operation: WorkspaceDocumentOperation, state: String): WorkspaceMutationResult {
            val result = commands.execute(runtime.capture().projectId, state, operation, "Membership", MutationAuthor.AGENT)
            after(); return result.mutation
        }
        override suspend fun softDeleteLayer(layerId: String, expectedState: String, taskId: String?) =
            run(WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", layerId) }), expectedState)
        override suspend fun restoreDeletedLayers(layerIds: List<String>?, expectedState: String, taskId: String?) =
            run(WorkspaceDocumentOperation("layer_restore", buildJsonObject { layerIds?.let { put("layer_ids", JsonArray(it.map(::JsonPrimitive))) } }), expectedState)
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject, id: String = "membership") = JsonObject(fields + buildJsonObject {
        val c = runtime.capture(); put("project_id", c.projectId); put("state", c.state); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data

    @Test fun createdLayerDeletionAndRestorationKeepPixelsOrderedEditsAndOldNodes() = runBlocking<Unit> {
        val runtime = fixture(); val created = born(runtime)
        val mesh = created.model.rig.puppet.drawables.single { created.model.rig.layerIdByDrawableId[it.id.raw] == "blank" }
        val edits = WorkspaceDocumentCommands(runtime)
        val authored = edits.execute(created.projectId, created.state, "Author born mesh", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "LayerAxis"); put("name", "Layer axis") }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("LayerAxis", 1) }
                putJsonObject("channels") { put("opacity", 0.4) }
            }) } }),
            WorkspaceDocumentOperation("path_put", buildJsonObject {
                put("id", "layerPath"); put("target", "mesh:${mesh.id.raw}")
                put("points", JsonArray(mesh.mesh!!.positions.asList().chunked(2).take(2).map { JsonArray(it.map(::JsonPrimitive)) }))
            }),
            WorkspaceDocumentOperation("vertex_group_update", buildJsonObject {
                put("target", "mesh:${mesh.id.raw}"); put("name", "pin"); put("kind", "pin"); put("rule", "fill"); put("value", 0.5)
            }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject {
                put("id", "layerGlue"); put("mesh_a", mesh.id.raw)
                put("mesh_b", created.model.rig.puppet.drawables.first { it.id != mesh.id }.id.raw); put("distance", 128)
            }),
            WorkspaceDocumentOperation("rig_edit_structure", buildJsonObject { putJsonArray("edits") { add(buildJsonObject {
                put("action", "static"); put("kind", "mesh"); put("id", created.model.rig.puppet.drawables.first { it.id != mesh.id }.id.raw)
                put("masked_by", buildJsonArray { add(mesh.id.raw) })
            }) } }),
        ), MutationAuthor.USER).capture
        val history = runtime.history().selections.map { it.node }
        val commands = WorkspaceLayerCommands(runtime)
        val deleted = commands.execute(authored.projectId, authored.state, delete("blank"), "Delete", MutationAuthor.AGENT).commit.capture
        assertEquals(setOf("blank"), deleted.document.deletedLayerIds)
        assertSame(authored.document.source, deleted.document.source)
        assertEquals(authored.document.rigEdits, deleted.document.rigEdits)
        assertTrue(deleted.model.rig.puppet.drawables.none { it.id == mesh.id })
        assertTrue(deleted.model.rig.puppet.deformPaths.none { it.drawableId == mesh.id })
        assertTrue(deleted.model.rig.puppet.vertexGroups.none { it.drawableId == mesh.id })
        assertTrue(deleted.model.rig.puppet.glues.isEmpty())
        assertTrue(deleted.model.rig.puppet.drawables.all { mesh.id !in it.maskedBy })
        assertTrue(deleted.model.rig.layerIdByDrawableId.values.none { it == "blank" })
        val noop = commands.execute(deleted.projectId, deleted.state, delete("blank"), "Delete again", MutationAuthor.USER)
        assertFalse(noop.commit.applied); assertEquals(deleted.state, noop.mutation.state)
        val restored = commands.execute(deleted.projectId, deleted.state, restore("blank"), "Restore", MutationAuthor.USER).commit.capture
        assertEquals(authored.revision, restored.revision)
        assertContentEquals(mesh.mesh!!.positions, restored.model.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions)
        assertEquals(authored.model.rig.puppet.deformPaths, restored.model.rig.puppet.deformPaths)
        assertEquals(authored.model.rig.puppet.vertexGroups, restored.model.rig.puppet.vertexGroups)
        val originalGlue = authored.model.rig.puppet.glues.single()
        val restoredGlue = restored.model.rig.puppet.glues.single()
        assertEquals(originalGlue.id, restoredGlue.id)
        assertEquals(originalGlue.meshA, restoredGlue.meshA); assertEquals(originalGlue.meshB, restoredGlue.meshB)
        assertEquals(originalGlue.intensity, restoredGlue.intensity)
        assertEquals(originalGlue.pairs.map { listOf(it.indexA, it.indexB, it.weightA, it.weightB) },
            restoredGlue.pairs.map { listOf(it.indexA, it.indexB, it.weightA, it.weightB) })
        assertEquals(authored.model.rig.puppet.drawables.map { it.id to it.maskedBy }, restored.model.rig.puppet.drawables.map { it.id to it.maskedBy })
        assertEquals(history, runtime.history().selections.take(history.size).map { it.node })
        val all = edits.execute(restored.projectId, restored.state, "Delete all", listOf(delete("strip"), delete("blank")), MutationAuthor.USER).capture
        assertTrue(all.model.rig.puppet.drawables.isEmpty())
        val returned = commands.execute(all.projectId, all.state, restore(), "Restore all", MutationAuthor.USER).commit.capture
        assertEquals(authored.revision, returned.revision)
        runtime.checkout(returned.projectId, returned.state, authored.historyHead)
        assertEquals(authored.revision, runtime.capture().revision)
    }

    @Test fun meshSettingsWhileLayersAreDeletedAppendTheSameReplacementAsVisibleLayers() = runBlocking<Unit> {
        for (deleteAll in listOf(false, true)) {
            val runtime = fixture(); val birth = born(runtime)
            val commands = WorkspaceDocumentCommands(runtime)
            val mesh = birth.model.rig.puppet.drawables.single { birth.model.rig.layerIdByDrawableId[it.id.raw] == "blank" }
            val created = commands.execute(birth.projectId, birth.state, "Bind created mesh", listOf(
                WorkspaceDocumentOperation("path_put", buildJsonObject {
                    put("id", "regeneratedPath"); put("target", "mesh:${mesh.id.raw}")
                    put("points", JsonArray(mesh.mesh!!.positions.asList().chunked(2).take(2).map { JsonArray(it.map(::JsonPrimitive)) }))
                }),
                WorkspaceDocumentOperation("vertex_group_update", buildJsonObject {
                    put("target", "mesh:${mesh.id.raw}"); put("name", "regeneratedPin"); put("kind", "pin"); put("rule", "fill"); put("value", 0.5)
                }),
            ), MutationAuthor.USER).capture
            val settings = WorkspaceDocumentOperation("settings_update", buildJsonObject {
                putJsonObject("changes") { put("meshSpacing", 16); put("meshEdgeMode", "TRIPLE") }
            })
            val visible = commands.execute(created.projectId, created.state, "Visible mesh settings", listOf(settings), MutationAuthor.USER).capture
            val original = runtime.checkout(visible.projectId, visible.state, created.historyHead)
            val deleted = commands.execute(original.projectId, original.state, "Delete layers",
                listOfNotNull(delete("blank"), if (deleteAll) delete("strip") else null), MutationAuthor.USER).capture
            val changed = commands.execute(deleted.projectId, deleted.state, "Deleted mesh settings", listOf(settings), MutationAuthor.USER).capture
            assertTrue(changed.model.rig.puppet.drawables.none { changed.model.rig.layerIdByDrawableId[it.id.raw] == "blank" })
            val restored = WorkspaceLayerCommands(runtime).execute(changed.projectId, changed.state, restore(), "Restore", MutationAuthor.USER).commit.capture
            val expectedBirth = visible.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            val actualBirth = restored.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            expectedBirth.forEach { (key, value) -> assertEquals(value, actualBirth[key], "Creation field $key, all deleted=$deleteAll") }
            assertEquals(visible.document.rigEdits, restored.document.rigEdits, "All deleted=$deleteAll")
            assertEquals(visible.document.settings, restored.document.settings, "All deleted=$deleteAll")
            assertEquals(visible.revision, restored.revision)
            val replayed = builder.build(restored.document)
            assertEquals(visible.model.rig.puppet.deformPaths, restored.model.rig.puppet.deformPaths)
            assertEquals(visible.model.rig.puppet.vertexGroups, restored.model.rig.puppet.vertexGroups)
            assertEquals(restored.model.rig.puppet.deformPaths, replayed.rig.puppet.deformPaths)
            assertEquals(restored.model.rig.puppet.vertexGroups, replayed.rig.puppet.vertexGroups)
            for (expected in visible.model.rig.puppet.drawables) {
                assertContentEquals(expected.mesh!!.positions, restored.model.rig.puppet.drawables.single { it.id == expected.id }.mesh!!.positions)
                assertContentEquals(expected.mesh.positions, replayed.rig.puppet.drawables.single { it.id == expected.id }.mesh!!.positions)
            }
            val prior = created.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            val after = restored.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            assertEquals(prior, after)
            assertTrue(restored.document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
            assertFalse(mesh.mesh!!.positions.contentEquals(restored.model.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions))
            assertEquals(created.document, runtime.history().selections.single { it.node.id == created.historyHead }.snapshot)
        }
    }

    @Test fun invalidMemberRejectedProjectionAndStaleStateNeverPublishMembership() = runBlocking<Unit> {
        val runtime = fixture(); val before = born(runtime); val history = runtime.history()
        val commands = WorkspaceLayerCommands(runtime)
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", delete("blank"), "Delete", MutationAuthor.USER) }
        assertFailsWith<IllegalArgumentException> { commands.execute(before.projectId, before.state, restore("missing"), "Restore", MutationAuthor.USER) }
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, delete("blank"), "Delete", MutationAuthor.USER) { _, _, _ -> error("Reject") } }
        val failure = assertFailsWith<WorkspaceBatchEditException> { WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state,
            "Bad batch", listOf(delete("blank"), restore("missing")), MutationAuthor.USER) }
        assertEquals(1, failure.index); assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun cancellationDuringRebuildAndConcurrentStateChangesRetainOriginalHistory() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = fixture { document ->
                if ("blank" in document.deletedLayerIds) { entered.complete(Unit); release.await() }
                builder.build(document)
            }
            val before = born(runtime); val history = runtime.history()
            WorkspaceOperations(Host(runtime)).use { operations ->
                val input = request(runtime, delete("blank").request)
                val job = operations.registry.invoke("layer_soft_delete", input, agent).data
                withTimeout(10000) { entered.await() }
                val expected = if (cancel) {
                    operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent)
                    before
                } else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", terminal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
                assertEquals(job.getValue("id"), operations.registry.invoke("layer_soft_delete", input, agent).data.getValue("id"))
            }
        }
    }

    @Test fun bothMembershipJobsKeepCompleteResultsAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        for (operation in WorkspaceLayerEdits.supported) for (cancel in listOf(true, false)) {
            val runtime = fixture(); val before = born(runtime)
            if (operation == "layer_restore") WorkspaceLayerCommands(runtime).execute(before.projectId, before.state, delete("blank"), "Delete", MutationAuthor.USER)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime, after = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed")
            })).use { operations ->
                val definition = operations.registry.definition(operation)
                assertTrue(definition.jobBacked && definition.batchable)
                val fields = if (operation == "layer_restore") restore("blank").request else delete("blank").request
                val input = request(runtime, fields)
                val job = operations.registry.invoke(operation, input, agent).data
                withTimeout(10000) { entered.await() }
                if (cancel) operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent)
                release.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject
                validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(listOf("blank"), result.getValue("affectedLayerIds").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(job.getValue("id"), operations.registry.invoke(operation, input, agent).data.getValue("id"))
                assertEquals(terminal, operations.wait(job))
            }
        }
    }
}
