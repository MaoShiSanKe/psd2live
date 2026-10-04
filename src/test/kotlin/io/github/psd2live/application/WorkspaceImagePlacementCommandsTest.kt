package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceImagePlacementCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private data class Fixture(val runtime: WorkspaceRuntime<RigPreviewModel>, val original: WorkspaceCapture<RigPreviewModel>, val ids: List<String>)
    private suspend fun fixture(imported: Boolean = false): Fixture {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val before = if (imported) WorkspaceCmo3Importer(runtime).import(writeCmo3Fixture(temporary.resolve("original.cmo3"), "Original"),
            Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER, initialConfig = PipelineConfig(atlasSize = 256)).capture
        else simulationFixture(runtime)
        val original = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Existing forms", simulationDrivingEdits(before), MutationAuthor.USER).capture
        val rgba = ByteArray(4 * 8 * 4)
        for (i in rgba.indices step 4) { rgba[i] = (i / 4 * 7).toByte(); rgba[i + 1] = 128.toByte(); rgba[i + 3] = -1 }
        val file = temporary.resolve("gradient.png").also { Files.write(it, PngCodec.write(RasterImage(4, 8, rgba))) }
        val added = WorkspaceImageLayerCommands(runtime).importImages(original.projectId, original.state, listOf(file, file), null, "Import images", MutationAuthor.USER)
        return Fixture(runtime, original, added.mutation.affectedLayerIds)
    }
    private suspend fun edit(f: Fixture, operation: WorkspaceDocumentOperation) = f.runtime.capture().let {
        WorkspaceImagePlacementCommands(f.runtime).execute(it.projectId, it.state, operation, "Image placement", MutationAuthor.USER)
    }
    private fun bounds(f: Fixture, x: Float = 20f, width: Float = 8f) = WorkspaceImageBounds(f.ids.last(), x, 12f, width, 16f, "Placed gradient")
    private fun unchanged(before: RigPreviewModel, after: RigPreviewModel) {
        for (value in listOf(-30f, 0f, 30f)) {
            val evaluator = CpuDeformationEvaluator(); val pose = mapOf(ParameterId("Drive") to value)
            val old = evaluator.evaluate(before.rig.puppet, pose).worldPositions
            val next = evaluator.evaluate(after.rig.puppet, pose).worldPositions
            old.forEach { (id, geometry) -> assertContentEquals(geometry, next.getValue(id), "Changed ${id.raw} at $value") }
        }
    }

    @Test fun absolutePlacementKeepsIdentityAndOriginalPixelsAcrossRepeatedResizingAndReplay() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val f = fixture(imported); val before = f.runtime.capture()
            val layer = before.document.source.layers.single { it.id.raw == f.ids.last() }
            val moved = edit(f, bounds(f).operation()).commit.capture
            unchanged(f.original.model, moved.model)
            validateRegisteredNeutral(moved.model, setOf(layer.id.raw))
            val shrunk = edit(f, bounds(f, width = 2f).operation()).commit.capture
            val restored = edit(f, WorkspaceImageBounds(layer.id.raw, layer.bounds.left.toFloat(), layer.bounds.top.toFloat(),
                layer.bounds.width.toFloat(), layer.bounds.height.toFloat(), layer.name).operation()).commit.capture
            assertContentEquals(layer.raster.rgba, restored.document.source.layers.single { it.id == layer.id }.raster.rgba)
            assertEquals(before.model.rig.puppet.drawables.map { it.id }.toSet(), restored.model.rig.puppet.drawables.map { it.id }.toSet())
            assertEquals(if (imported) 2 else 1, restored.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
            unchanged(restored.model, builder.build(restored.document))
            assertEquals(layer.bounds, restored.document.placementSource!!.layers.single { it.id == layer.id }.bounds)
            assertNotEquals(shrunk.state, restored.state)
            val history = f.runtime.history()
            assertFalse(edit(f, WorkspaceImageBounds(layer.id.raw, layer.bounds.left.toFloat(), layer.bounds.top.toFloat(),
                layer.bounds.width.toFloat(), layer.bounds.height.toFloat(), layer.name).operation()).commit.applied)
            assertEquals(history, f.runtime.history())
        }
    }

    @Test fun cancelRemovesTheWholeBatchInOneStepAndUndoRestoresItsLastPlacement() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val f = fixture(imported); edit(f, bounds(f).operation()); val before = f.runtime.capture(); val history = f.runtime.history()
            val operation = WorkspaceDocumentOperation("layer_cancel_import", buildJsonObject { put("layer_ids", JsonArray(f.ids.map(::JsonPrimitive))) })
            val cancellation = edit(f, operation).commit
            val cancelled = cancellation.capture
            val batchResult = WorkspaceDocumentCommands.mutationResult(before, cancellation, "Cancel images", listOf(operation)).batchResult(1)
            assertTrue(f.ids.all { id -> JsonPrimitive("layer:$id") in batchResult.getValue("changed").jsonArray })
            assertEquals(history.selections.size + 1, f.runtime.history().selections.size)
            assertTrue(cancelled.document.source.layers.none { it.id.raw in f.ids })
            assertTrue(cancelled.document.rigEdits.authoringJournal.none { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
            assertTrue(cancelled.document.placementSource!!.layers.isEmpty())
            unchanged(f.original.model, cancelled.model)
            assertEquals(f.original.model.rig.puppet.drawables.map { it.id }.toSet(), cancelled.model.rig.puppet.drawables.map { it.id }.toSet())
            assertFalse(edit(f, operation).commit.applied)
            val restored = f.runtime.checkout(cancelled.projectId, cancelled.state, before.historyHead)
            unchanged(before.model, restored.model)
        }
    }

    @Test fun badBatchMemberProjectionRejectionAndStalePlacementPublishNothing() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history()
        val commands = WorkspaceDocumentCommands(f.runtime)
        assertFailsWith<WorkspaceBatchEditException> { commands.execute(before.projectId, before.state, "Failed placement", listOf(bounds(f).operation(),
            WorkspaceDocumentOperation("parameter_delete", buildJsonObject { put("parameter_id", "missing") })), MutationAuthor.AGENT) }
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
        assertFailsWith<IllegalStateException> { WorkspaceImagePlacementCommands(f.runtime).execute(before.projectId, before.state, bounds(f).operation(), "Rejected", MutationAuthor.USER,
            beforeCommit = { _, _, _ -> error("Projection rejected") }) }
        assertEquals(before, f.runtime.capture())
        f.runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("external", true) })
        assertFailsWith<WorkspaceConflict> { WorkspaceImagePlacementCommands(f.runtime).execute(before.projectId, before.state,
            WorkspaceDocumentOperation("layer_set_bounds", JsonObject(emptyMap())), "Stale placement", MutationAuthor.AGENT) }
    }

    @Test fun originalPlacementPixelsAndOptionalV1FieldSurviveArchiveWithoutInputFiles() = runBlocking<Unit> {
        val f = fixture(); val imported = f.runtime.capture(); val original = imported.document.source.layers.single { it.id.raw == f.ids.last() }
        edit(f, bounds(f, width = 2f).operation()); val before = f.runtime.capture()
        val store = WorkspaceStore(temporary.resolve("store")); val archive = temporary.resolve("placement.psd2live"); val repository = ProjectRepository()
        repository.save(ProjectSaveCapture(before.projectId, f.runtime.history(), JsonObject(emptyMap()), null, store), archive)
        Files.delete(temporary.resolve("gradient.png"))
        repository.open(archive).use { opened ->
            val document = opened.history.head().snapshot
            assertEquals(before.revision, WorkspaceRevisions.of(document))
            assertContentEquals(original.raster.rgba, document.placementSource!!.layers.single { it.id == original.id }.raster.rgba)
            assertNull(opened.history.state().selections.single { it.node.id == f.original.historyHead }.snapshot.placementSource)
            assertEquals(f.original.revision, opened.history.state().selections.single { it.node.id == f.original.historyHead }.node.revisionId)
            unchanged(before.model, builder.build(document))
        }
    }

    @Test fun laterMeshBindingPreventsMoveAndCancellationAndInvalidDimensionsNeverAllocate() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val mesh = before.model.rig.puppet.drawables.single { before.model.rig.layerIdByDrawableId[it.id.raw] == f.ids.last() }.id.raw
        WorkspaceDocumentCommands(f.runtime).execute(before.projectId, before.state, "Bind", listOf(WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
            put("id", "bound-parent"); put("name", "Bound parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(mesh) }
        })), MutationAuthor.USER)
        val bound = f.runtime.capture(); val history = f.runtime.history()
        assertFailsWith<WorkspaceBatchEditException> { edit(f, bounds(f).operation()) }
        assertFailsWith<WorkspaceBatchEditException> { edit(f, WorkspaceDocumentOperation("layer_cancel_import", buildJsonObject { put("layer_ids", JsonArray(f.ids.map(::JsonPrimitive))) })) }
        assertFailsWith<WorkspaceBatchEditException> { edit(f, bounds(f, width = 1_000_000f).operation()) }
        assertEquals(bound, f.runtime.capture()); assertEquals(history, f.runtime.history())
    }

    @Test fun sessionPreviewCannotChangePersistenceAndLatePreviewCannotOverwriteACommit() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val rendered = mutableListOf<RigPreviewModel>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = WorkspaceImagePlacementSession(f.runtime, before, f.ids, scope, { _, model -> synchronized(rendered) { rendered += model } }, { _, _, _ -> }, {},
                build = { document, current -> entered.complete(Unit); release.await(); builder.build(document, current) })
            val pending = session.preview(bounds(f, width = 12f)); entered.await()
            assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
            val committed = session.commit(bounds(f), "Placed").await()
            release.complete(Unit); assertFailsWith<CancellationException> { pending.await() }
            assertEquals(committed.state, f.runtime.capture().state); assertTrue(rendered.isEmpty())
            session.dismiss(); assertEquals(f.runtime.capture().model, rendered.single())
        } finally { scope.cancel() }
    }

    @Test fun sessionOwnCommitsAdvanceItsStateButExternalEditAndReopenRejectOldRequests() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = WorkspaceImagePlacementSession(f.runtime, before, f.ids, scope, { _, _ -> }, { _, _, _ -> }, {})
            val first = session.commit(bounds(f, x = 10f), "First")
            val second = session.commit(bounds(f, x = 15f), "Second")
            first.await(); second.await(); session.awaitIdle()
            assertEquals(15, f.runtime.capture().document.source.layers.single { it.id.raw == f.ids.last() }.bounds.left)
            val owned = f.runtime.capture()
            val changed = f.runtime.updateAuxiliary(owned.projectId, owned.state, buildJsonObject { put("external", true) })
            assertFailsWith<WorkspaceConflict> { session.commit(bounds(f, x = 18f), "Stale").await() }
            assertEquals(changed, f.runtime.capture())
            session.dismiss()
            val reopenedSession = WorkspaceImagePlacementSession(f.runtime, changed, f.ids, scope, { _, _ -> }, { _, _, _ -> }, {})
            val reopened = f.runtime.install(changed.state, changed.projectId, changed.document, changed.model, f.runtime.history(), discardUnsaved = true)
            assertFailsWith<WorkspaceConflict> { reopenedSession.cancel("Old cancel").await() }
            assertEquals(reopened, f.runtime.capture())
        } finally { scope.cancel() }
    }

    @Test fun legacyImagePlacementPreservesTheOriginalGenerationFramesWithoutDuplicatingMeshes() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture()
        val legacy = before.document.copy(placementSource = null, generationSource = before.document.source,
            rigEdits = before.document.rigEdits.copy(splitBaselineLayerIds = before.document.source.layers.mapTo(HashSet()) { it.id.raw }))
        val loaded = f.runtime.install(before.state, before.projectId, legacy, builder.build(legacy), discardUnsaved = true)
        val moved = edit(f, bounds(f).operation()).commit.capture
        val originalIds = loaded.model.rig.puppet.drawables.filterNot { loaded.model.rig.layerIdByDrawableId[it.id.raw] == f.ids.last() }.mapTo(HashSet()) { it.id }
        for (value in listOf(-30f, 0f, 30f)) {
            val pose = mapOf(ParameterId("Drive") to value); val evaluator = CpuDeformationEvaluator()
            val expected = evaluator.evaluate(loaded.model.rig.puppet, pose).worldPositions
            val actual = evaluator.evaluate(moved.model.rig.puppet, pose).worldPositions
            originalIds.forEach { assertContentEquals(expected.getValue(it), actual.getValue(it)) }
        }
        validateRegisteredNeutral(moved.model, setOf(f.ids.last()))
        assertEquals(loaded.model.rig.puppet.drawables.map { it.id }.toSet(), moved.model.rig.puppet.drawables.map { it.id }.toSet())
        unchanged(moved.model, builder.build(moved.document))
        val cancel = WorkspaceDocumentOperation("layer_cancel_import", buildJsonObject { putJsonArray("layer_ids") { f.ids.forEach { add(it) } } })
        val cancelled = edit(f, cancel).commit.capture
        assertTrue(f.ids.all { it in cancelled.document.deletedLayerIds })
        assertFalse(edit(f, cancel).commit.applied)
        unchanged(cancelled.model, builder.build(cancelled.document))
    }

    @Test fun rejectedCommitAllowsRetryAndCancelledUiWaiterDoesNotCancelProcessOwnedCommit() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            var reject = true
            val session = WorkspaceImagePlacementSession(f.runtime, before, f.ids, scope, { _, _ -> },
                { _, _, _ -> if (reject) error("Rejected placement") }, { entered.complete(Unit); runBlocking { release.await() } })
            assertFailsWith<IllegalStateException> { session.commit(bounds(f), "Rejected").await() }
            assertEquals(before, f.runtime.capture())
            reject = false
            session.preview(bounds(f)).await()
            val pending = session.commit(bounds(f), "Retry")
            entered.await()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { pending.await() }
            waiter.cancelAndJoin()
            assertFalse(pending.isCancelled)
            assertNotEquals(before.state, f.runtime.capture().state)
            release.complete(Unit); pending.await(); session.awaitIdle(); session.dismiss()
            assertFailsWith<IllegalStateException> { session.preview(bounds(f)) }
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun staticSkeletonAndSimulationBindingsCannotBeOverwrittenByPlacement() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val id = f.ids.last()
        val mesh = before.model.rig.puppet.drawables.single { before.model.rig.layerIdByDrawableId[it.id.raw] == id }.id.raw
        val overlays = listOf(
            before.document.rigEdits.copy(skeleton = SkeletonSpec(bones = listOf(SkeletonBone("bone", "Bone", null, BoneRole.HAND,
                headX = 0f, headY = 0f, tailX = 0f, tailY = 10f, drawableIds = listOf(mesh))))),
            before.document.rigEdits.copy(simEdits = listOf(io.github.psd2live.core.sim.RigSimEdit("sim", "Sim", io.github.psd2live.core.sim.SimKind.HAIR, listOf(id), autoBake = false)))
        )
        for (overlay in overlays) {
            assertFailsWith<IllegalArgumentException> { WorkspaceImagePlacementEdits.apply(before.document.copy(rigEdits = overlay), before.model, bounds(f).operation()) {} }
        }
        assertEquals(before, f.runtime.capture())
    }

    @Test fun placementAndCancelJobsRetainTheCasResultAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        val f = fixture()
        val operations = listOf(bounds(f).operation(), WorkspaceDocumentOperation("layer_cancel_import",
            buildJsonObject { put("layer_ids", JsonArray(f.ids.map(::JsonPrimitive))) }))
        WorkspaceJobs().use { jobs ->
            for (operation in operations) {
                val before = f.runtime.capture()
                val job = jobs.start(operation.operation, before.projectId, before.state, WorkspaceJobResultSchemas.result(operation.operation)) {
                    val context = currentCoroutineContext()
                    withContext(WorkspaceLayerJobExecution(operation.operation, context[WorkspaceJobCompletion]!!)) {
                        WorkspaceImagePlacementCommands(f.runtime).execute(before.projectId, before.state, operation, "Placement job", MutationAuthor.AGENT,
                            beforeCommit = { _, _, _ -> context.cancel() })
                        error("Refresh failed after CAS")
                    }
                }
                val terminal = jobs.wait(job.id)
                assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status, terminal.error.toString())
                val result = terminal.result!!.data
                assertEquals(f.runtime.capture().state, result.text("state"))
                assertTrue(result.getValue("affectedLayerIds").jsonArray.isNotEmpty())
                assertEquals(result, jobs.cancel(job.id).result!!.data)
            }
        }
    }

}
