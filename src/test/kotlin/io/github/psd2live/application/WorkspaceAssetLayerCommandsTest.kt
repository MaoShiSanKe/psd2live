package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class WorkspaceAssetLayerCommandsTest {
    @TempDir lateinit var temporary: Path
    private data class Fixture(val runtime: WorkspaceRuntime<RigPreviewModel>, val store: WorkspaceStore,
                               val asset: String, val registration: String, val reference: String) {
        val sessions = WorkspaceAssetSessions(runtime)
        val commands = WorkspaceAssetLayerCommands(runtime) { sessions.workflow(it, store) }
        val batch = WorkspaceDocumentCommands(runtime, assetResources = { sessions.workflow(it, store) })
        suspend fun edit(id: String, a: JsonObject, project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }) =
            runtime.capture().let { commands.execute(it.projectId, it.state, WorkspaceDocumentOperation(id, a), "Asset layer", MutationAuthor.USER,
                beforeCommit = project) }
        fun add(id: String = "decoration", name: String = "Decoration") = buildJsonObject {
            put("asset_id", asset); put("registration_id", registration); put("name", name); put("layer_id", id)
        }
    }
    private suspend fun fixture(importedModel: Boolean = false): Fixture {
        val builder = WorkspacePreviewBuilder(); val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val initial = if (importedModel) WorkspaceCmo3Importer(runtime).import(writeCmo3Fixture(temporary.resolve("imported.cmo3"), "Original"),
            Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER, initialConfig = PipelineConfig(atlasSize = 256)).capture
        else simulationFixture(runtime)
        val document = initial.document.copy(settings = JsonObject(initial.document.settings + ("meshOnly" to JsonPrimitive(false))))
        val loaded = runtime.install(initial.state, initial.projectId, document, builder.build(document), discardUnsaved = true)
        WorkspaceDocumentCommands(runtime).execute(loaded.projectId, loaded.state, "Driving forms", simulationDrivingEdits(loaded), MutationAuthor.USER)
        val store = WorkspaceStore(temporary.resolve("store")); val sessions = WorkspaceAssetSessions(runtime)
        suspend fun asset(id: String, a: JsonObject) = runtime.capture().let { sessions.execute(it.projectId, it.state, store, id, a) }
        val reference = asset("asset_prepare_reference", buildJsonObject {
            put("layer_id", runtime.capture().document.source.layers.first().id.raw); put("piece_id", "decoration"); put("background_color", "#11ccff"); put("target_long_edge", 128)
            putJsonObject("target_anchors") {
                putJsonObject("root") { put("x", 0); put("y", 0) }; putJsonObject("tip") { put("x", 12); put("y", 72) }
            }
        }).metadata.text("id")
        val image = BufferedImage(12, 72, BufferedImage.TYPE_INT_ARGB).also { i ->
            for (y in 2..69) for (x in 2..9) i.setRGB(x, y, 0xffff8844.toInt())
        }
        val imported = asset("asset_import_png", buildJsonObject {
            put("reference_id", reference); put("png_base64", Base64.getEncoder().encodeToString(png(image))); put("require_transparency", true)
        }).metadata.text("assetId")
        val registration = asset("asset_register", buildJsonObject { put("asset_id", imported); put("mode", "frame"); put("allow_stretch", importedModel) }).metadata.text("id")
        return Fixture(runtime, store, imported, registration, reference)
    }
    private fun positions(model: RigPreviewModel, value: Float) = CpuDeformationEvaluator().evaluate(model.rig.puppet,
        mapOf(ParameterId("Drive") to value)).worldPositions
    private fun unchanged(before: RigPreviewModel, after: RigPreviewModel) {
        for (value in listOf(-30f, 0f, 30f)) {
            val old = positions(before, value); val next = positions(after, value)
            old.forEach { (id, shape) -> assertContentEquals(shape, next[id], "Existing geometry changed: ${id.raw} at $value") }
        }
    }

    @Test fun additionFixesNewAndExistingIdsAndPreservesBoundGeometryThroughRepeatedNamesAndReplay() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val count = f.runtime.history().selections.size
        val first = f.edit("layer_add_from_asset", f.add())
        assertEquals(count + 1, f.runtime.history().selections.size); unchanged(before.model, first.commit.capture.model)
        assertEquals(listOf("decoration"), first.mutation.affectedLayerIds); assertTrue(first.mutation.affectedObjectIds.any { it.startsWith("mesh:") })
        assertNotNull(first.commit.capture.document.generationSource)
        val second = f.edit("layer_add_from_asset", f.add("second", "Decoration"))
        unchanged(first.commit.capture.model, second.commit.capture.model)
        unchanged(second.commit.capture.model, WorkspacePreviewBuilder().build(second.commit.capture.document))
        assertEquals(2, second.commit.capture.document.source.layers.count { it.name == "Decoration" })
        assertEquals(before.document, f.runtime.history().selections.single { it.node.id == before.historyHead }.snapshot)
    }

    @Test fun orderedBatchAddsPlacesAndFinalizesOnceAndBadLaterMemberPublishesNoPrefix() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history()
        val add = WorkspaceDocumentOperation("layer_add_from_asset", f.add())
        val finalize = WorkspaceDocumentOperation("layer_finalize_placement", buildJsonObject { put("layer_id", "decoration") })
        assertFailsWith<WorkspaceBatchEditException> {
            f.batch.execute(before.projectId, before.state, "Failed batch", listOf(add, finalize,
                WorkspaceDocumentOperation("parameter_delete", buildJsonObject { put("parameter_id", "missing") })), MutationAuthor.AGENT)
        }
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
        val committed = f.batch.execute(before.projectId, before.state, "Add and finalize", listOf(add, finalize), MutationAuthor.USER)
        assertEquals(history.selections.size + 1, f.runtime.history().selections.size)
        assertTrue(committed.capture.document.rigEdits.assetLayers.getValue("decoration").flag("placement_finalized"))
        unchanged(before.model, committed.capture.model)
        val same = f.edit("layer_finalize_placement", finalize.request)
        assertFalse(same.commit.applied); assertEquals(committed.capture.state, same.mutation.state)
    }

    @Test fun relocationUsesOriginalPixelsRetainsExistingMotionAndRepeatedPlacementIsNoop() = runBlocking<Unit> {
        val f = fixture(); val initial = f.runtime.capture(); f.edit("layer_add_from_asset", f.add())
        val source = f.runtime.capture()
        val registration = f.sessions.execute(source.projectId, source.state, f.store, "asset_register", buildJsonObject {
            put("asset_id", f.asset); put("mode", "absolute")
            putJsonObject("transform") { put("x", 20); put("y", 5); put("scale_x", 1) }
        }).metadata.text("id")
        val args = buildJsonObject { put("layer_id", "decoration"); put("registration_id", registration) }
        val moved = f.edit("layer_set_placement", args)
        unchanged(initial.model, moved.commit.capture.model)
        val layer = moved.commit.capture.document.source.layers.single { it.id.raw == "decoration" }
        assertEquals(22, layer.bounds.left); assertEquals(7, layer.bounds.top)
        assertContentEquals(source.document.source.layers.single { it.id.raw == "decoration" }.raster.rgba, layer.raster.rgba)
        assertFalse(f.edit("layer_set_placement", args).commit.applied)
        unchanged(moved.commit.capture.model, WorkspacePreviewBuilder().build(moved.commit.capture.document))
    }

    @Test fun projectionRejectionAndStaleStatePreserveDocumentHistoryAndAssetResources() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history(); val catalog = f.store.existingAssetCatalog(before.projectId)
        assertFailsWith<IllegalStateException> { f.edit("layer_add_from_asset", f.add()) { _, _, _ -> error("Reject projection") } }
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
        val changed = f.runtime.updateAuxiliary(before.projectId, before.state, JsonObject(before.auxiliary + ("concurrent" to JsonPrimitive(true))))
        assertFailsWith<WorkspaceConflict> { f.commands.execute(changed.projectId, before.state,
            WorkspaceDocumentOperation("layer_add_from_asset", JsonObject(emptyMap())), "Stale", MutationAuthor.AGENT) }
        assertEquals(changed, f.runtime.capture()); assertEquals(catalog, f.store.existingAssetCatalog(before.projectId))
    }

    @Test fun successfulLayerJobRetainsGeneratedHandlesAfterLateCancellation() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture()
        WorkspaceJobs().use { jobs ->
            val job = jobs.start("layer_add_from_asset", before.projectId, before.state, WorkspaceJobResultSchemas.result("layer_add_from_asset")) {
                val context = currentCoroutineContext()
                withContext(WorkspaceLayerJobExecution("layer_add_from_asset", context[WorkspaceJobCompletion]!!)) {
                    f.edit("layer_add_from_asset", f.add()) { _, _, _ -> context.cancel() }
                    error("Cancellation should surface after commit")
                }
            }
            val terminal = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status, terminal.error.toString())
            val result = terminal.result!!.data
            assertEquals(f.runtime.capture().state, result.text("state")); assertTrue(result.getValue("affectedObjectIds").jsonArray.isNotEmpty())
            assertEquals(result, jobs.cancel(job.id).result!!.data)
        }
    }

    @Test fun savedAssetLayersAndBindingsReopenAtOriginalNodeAndRevision() = runBlocking<Unit> {
        val f = fixture(); val original = f.runtime.capture(); f.edit("layer_add_from_asset", f.add())
        val before = f.runtime.capture(); val history = f.runtime.history(); val repository = ProjectRepository()
        val target = temporary.resolve("asset-layer.psd2live")
        repository.save(ProjectSaveCapture(before.projectId, history, JsonObject(emptyMap()), null, f.store, auxiliary = before.auxiliary), target)
        repository.open(target).use { opened ->
            assertEquals(before.historyHead, opened.history.head().node.id)
            assertEquals(before.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            unchanged(before.model, WorkspacePreviewBuilder().build(opened.history.head().snapshot))
            assertEquals(original.revision, opened.history.state().selections.first { it.node.id == original.historyHead }.node.revisionId)
        }
    }

    private suspend fun registerAbsolute(f: Fixture, x: Int, y: Int, scale: Float = 1f): String = f.runtime.capture().let { before ->
        f.sessions.execute(before.projectId, before.state, f.store, "asset_register", buildJsonObject {
            put("asset_id", f.asset); put("mode", "absolute")
            putJsonObject("transform") { put("x", x); put("y", y); put("scale_x", scale) }
        }).metadata.text("id")
    }

    private suspend fun authoredParent(f: Fixture): WorkspaceCapture<RigPreviewModel> {
        val before = f.runtime.capture(); val mesh = before.model.rig.puppet.drawables.first().id.raw
        val parent = WorkspaceDocumentCommands(f.runtime).execute(before.projectId, before.state, "Placement parent", listOf(
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                put("id", "placement-parent"); put("name", "Placement parent"); put("rows", 2); put("columns", 2)
                putJsonArray("meshes") { add(mesh) }
            })), MutationAuthor.USER).capture
        val cp = (parent.model.rig.puppet.deformers.single { it.id.raw == "placement-parent" } as org.umamo.runtime.model.Deformer.Warp)
            .geometryGrid!!.cells.first().form.controlPoints
        return WorkspaceDocumentCommands(f.runtime).execute(parent.projectId, parent.state, "Parent motion", listOf(
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for ((value, shift) in listOf(-30f to -4f, 30f to 4f)) add(buildJsonObject {
                    put("op", "set"); put("target", "warp:placement-parent"); putJsonObject("key") { put("Drive", value) }
                    putJsonObject("geometry") { put("controlPoints", JsonArray(cp.mapIndexed { i, coordinate -> JsonPrimitive(coordinate + if (i % 2 == 0) shift else 0f) })) }
                })
            } })), MutationAuthor.USER).capture
    }

    @Test fun authoredParentAssetCanBePlacedTwiceWithoutDuplicateCreationOrLosingInheritedMotion() = runBlocking<Unit> {
        val f = fixture(); val parent = authoredParent(f)
        val added = f.edit("layer_add_from_asset", JsonObject(f.add() + ("parent_deformer_id" to JsonPrimitive("placement-parent"))))
        unchanged(parent.model, added.commit.capture.model)
        val firstRegistration = registerAbsolute(f, 20, 5)
        val moved = f.edit("layer_set_placement", buildJsonObject { put("layer_id", "decoration"); put("registration_id", firstRegistration) })
        unchanged(parent.model, moved.commit.capture.model)
        validateRegisteredNeutral(moved.commit.capture.model, setOf("decoration"))
        val drawable = moved.commit.capture.model.rig.puppet.drawables.single { moved.commit.capture.model.rig.layerIdByDrawableId[it.id.raw] == "decoration" }
        assertEquals("placement-parent", drawable.parentDeformerId!!.raw)
        assertTrue(drawable.geometryGrid?.axes.orEmpty().isEmpty())
        val rest = positions(moved.commit.capture.model, 0f).getValue(drawable.id)
        val motion = positions(moved.commit.capture.model, 30f).getValue(drawable.id)
        assertTrue(rest.indices.filter { it % 2 == 0 }.all { motion[it] - rest[it] > 0.1f })
        val secondRegistration = registerAbsolute(f, 8, 12)
        val again = f.edit("layer_set_placement", buildJsonObject { put("layer_id", "decoration"); put("registration_id", secondRegistration) })
        unchanged(parent.model, again.commit.capture.model)
        assertEquals(drawable.id, again.commit.capture.model.rig.puppet.drawables.single { again.commit.capture.model.rig.layerIdByDrawableId[it.id.raw] == "decoration" }.id)
        assertEquals(1, again.commit.capture.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
        assertEquals(1, again.commit.capture.document.generationSource!!.layers.count { it.id.raw == "decoration" })
        unchanged(again.commit.capture.model, WorkspacePreviewBuilder().build(again.commit.capture.document))
        val finalized = f.edit("layer_finalize_placement", buildJsonObject { put("layer_id", "decoration") })
        assertTrue(finalized.commit.applied)
        val capture = finalized.commit.capture
        val repository = ProjectRepository(); val archive = temporary.resolve("authored-parent.psd2live")
        repository.save(ProjectSaveCapture(capture.projectId, f.runtime.history(), JsonObject(emptyMap()), null, f.store, auxiliary = capture.auxiliary), archive)
        repository.open(archive).use { opened ->
            assertEquals(capture.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            unchanged(capture.model, WorkspacePreviewBuilder().build(opened.history.head().snapshot))
        }
        val exported = PSD2LivePipeline().run(capture.document.source, "Asset parent", temporary.resolve("parent-export"), capture.document.config().copy(exportMoc3 = false))
        val cmo3 = exported.exportedFiles.single { it.path.toString().endsWith(".cmo3") }.path
        val readback = Cmo3ModelImport.read(java.nio.file.Files.readAllBytes(cmo3)).puppet
        for (value in listOf(-30f, 0f, 30f)) {
            val expected = positions(capture.model, value)
            val actual = CpuDeformationEvaluator().evaluate(readback, mapOf(ParameterId("Drive") to value)).worldPositions
            expected.forEach { (id, geometry) ->
                val replay = actual.getValue(id)
                assertEquals(geometry.size, replay.size)
                assertTrue(geometry.indices.all { kotlin.math.abs(geometry[it] - replay[it]) < 0.05f }, "Export motion changed for ${id.raw} at $value")
            }
        }
    }

    @Test fun importedModelAssetAdditionAndRelocationSurviveArchiveReplay() = runBlocking<Unit> {
        val f = fixture(importedModel = true); val original = f.runtime.capture()
        val added = f.edit("layer_add_from_asset", f.add())
        assertEquals(original.model.rig.puppet.drawables.size + 1, added.commit.capture.model.rig.puppet.drawables.size)
        val registration = registerAbsolute(f, 10, 4)
        val moved = f.edit("layer_set_placement", buildJsonObject { put("layer_id", "decoration"); put("registration_id", registration) })
        unchanged(original.model, moved.commit.capture.model)
        validateRegisteredNeutral(moved.commit.capture.model, setOf("decoration"))
        val repository = ProjectRepository(); val archive = temporary.resolve("imported-assets.psd2live")
        repository.save(ProjectSaveCapture(moved.commit.capture.projectId, f.runtime.history(), JsonObject(emptyMap()), null, f.store,
            auxiliary = moved.commit.capture.auxiliary), archive)
        repository.open(archive).use { opened ->
            val document = opened.history.head().snapshot
            assertEquals(moved.commit.capture.revision, WorkspaceRevisions.of(document))
            val replay = WorkspacePreviewBuilder().build(document)
            unchanged(moved.commit.capture.model, replay)
            validateRegisteredNeutral(replay, setOf("decoration"))
        }
    }

    @Test fun orderedComplexParentBatchUsesItsPrecedingCandidateAndPublishesNoFailedPrefix() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history()
        val mesh = before.model.rig.puppet.drawables.first().id.raw
        val parent = WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
            put("id", "batch-parent"); put("name", "Batch parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(mesh) }
        })
        val add = WorkspaceDocumentOperation("layer_add_from_asset", JsonObject(f.add() + ("parent_deformer_id" to JsonPrimitive("batch-parent"))))
        val finalize = WorkspaceDocumentOperation("layer_finalize_placement", buildJsonObject { put("layer_id", "decoration") })
        assertFailsWith<WorkspaceBatchEditException> { f.batch.execute(before.projectId, before.state, "Failed placement", listOf(parent, add,
            WorkspaceDocumentOperation("parameter_delete", buildJsonObject { put("parameter_id", "missing") })), MutationAuthor.AGENT) }
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
        val result = f.batch.execute(before.projectId, before.state, "Parent and asset", listOf(parent, add, finalize), MutationAuthor.AGENT)
        assertEquals(history.selections.size + 1, f.runtime.history().selections.size)
        assertEquals("batch-parent", result.capture.model.rig.puppet.drawables.single { result.capture.model.rig.layerIdByDrawableId[it.id.raw] == "decoration" }.parentDeformerId!!.raw)
        validateRegisteredNeutral(result.capture.model, setOf("decoration"))
    }

    @Test fun laterCanvasBindingPreventsGeometryReplacementWithoutChangingState() = runBlocking<Unit> {
        val f = fixture(); authoredParent(f)
        f.edit("layer_add_from_asset", JsonObject(f.add() + ("parent_deformer_id" to JsonPrimitive("placement-parent"))))
        val added = f.runtime.capture(); val id = added.model.rig.puppet.drawables.single { added.model.rig.layerIdByDrawableId[it.id.raw] == "decoration" }.id.raw
        WorkspaceDocumentCommands(f.runtime).execute(added.projectId, added.state, "Bind decoration", listOf(
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                put("id", "later-parent"); put("name", "Later parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(id) }
            })), MutationAuthor.USER)
        val registration = registerAbsolute(f, 20, 5); val before = f.runtime.capture(); val history = f.runtime.history()
        assertFailsWith<WorkspaceBatchEditException> { f.edit("layer_set_placement", buildJsonObject { put("layer_id", "decoration"); put("registration_id", registration) }) }
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
    }

    @Test fun legacyOversizedPlacementIsRejectedBeforeAllocatingNormalizedPixels() = runBlocking<Unit> {
        val f = fixture(); val before = f.runtime.capture(); val history = f.runtime.history()
        val original = f.sessions.workflow(before, f.store).asset(f.asset)
        val asset = original.copy(public = original.public.copy(placement = original.public.placement.copy(canvasRect = Bounds(0f, 0f, 20000f, 20000f))))
        val failure = assertFailsWith<IllegalArgumentException> {
            WorkspaceDocumentCommands(f.runtime).executeCandidate(before.projectId, before.state, "Oversized legacy placement", MutationAuthor.USER,
                mutation = { document, _ -> document.addLayer(asset, WorkspaceAddLayerRequest(f.asset, before.state, "Decoration")).first })
        }
        assertTrue(failure.message!!.contains("16 megapixels"))
        assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
    }

    @Test fun cancellationDuringAssetNormalizationPublishesNeitherPixelsNorCreationRecords() = runBlocking<Unit> {
        val f = fixture(); authoredParent(f); val before = f.runtime.capture(); val history = f.runtime.history()
        var checkpoints = 0
        val observer = object : WorkspaceRasterWork {
            override fun checkpoint() { if (++checkpoints == 5) throw CancellationException("Cancelled normalization") }
            override fun progress(fraction: Float, message: String) {}
        }
        val commands = WorkspaceDocumentCommands(f.runtime, rasterWork = observer, assetResources = { f.sessions.workflow(it, f.store) })
        assertFailsWith<CancellationException> { commands.execute(before.projectId, before.state, "Cancelled asset", listOf(
            WorkspaceDocumentOperation("layer_add_from_asset", JsonObject(f.add() + ("parent_deformer_id" to JsonPrimitive("placement-parent"))))), MutationAuthor.AGENT) }
        assertEquals(5, checkpoints); assertEquals(before, f.runtime.capture()); assertEquals(history, f.runtime.history())
    }
}
