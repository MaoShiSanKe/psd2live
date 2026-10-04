package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.*

class WorkspaceAssetSessionsTest {
    @TempDir lateinit var temporary: Path
    private val store get() = WorkspaceStore(temporary.resolve("store"))
    private suspend fun fixture(legacy: Boolean = false): WorkspaceRuntime<RigPreviewModel> {
        val config = PipelineConfig(atlasSize = 256, meshOnly = true, generatePhysics = false)
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true, 0,
            LayerBounds(4, 4, 16, 16), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(16, 16, ByteArray(1024) { if (it % 4 == 3) -1 else 60 }), null, null, false)
        val document = WorkspaceDocument(WorkspaceSourceArt(32, 32, listOf(layer), emptyList()), emptyMap(), emptySet(),
            emptyMap(), emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "project", document, builder.build(document),
            auxiliary = if (legacy) JsonObject(emptyMap()) else buildJsonObject { put("assetCatalog", WorkspaceAssetCatalog().encode()) })
        return runtime
    }
    private fun prepareArguments() = buildJsonObject {
        put("layer_id", "art"); put("piece_id", "decoration"); put("background_color", "#11ccff"); put("target_long_edge", 128)
        putJsonObject("target_anchors") {
            putJsonObject("root") { put("x", 4); put("y", 4) }; putJsonObject("tip") { put("x", 20); put("y", 20) }
        }
    }
    private fun generated() = png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 3..12) for (x in 3..12) image.setRGB(x, y, 0xffff3366.toInt())
    })
    private suspend fun execute(runtime: WorkspaceRuntime<RigPreviewModel>, id: String, args: JsonObject,
                                beforeCommit: (WorkspaceCapture<RigPreviewModel>) -> Unit = {}): WorkspaceWorkflowResult {
        val capture = runtime.capture()
        val result = WorkspaceAssetSessions(runtime).execute(capture.projectId, capture.state, store, id, args, beforeCommit)
        validateOperationSchema(result.metadata, WorkspaceAssetResultSchemas.forOperation(id)!!)
        return result
    }
    private suspend fun prepared(runtime: WorkspaceRuntime<RigPreviewModel>) =
        execute(runtime, "asset_prepare_reference", prepareArguments()).metadata.text("id")
    private fun importArguments(reference: String) = buildJsonObject {
        put("reference_id", reference); put("png_base64", Base64.getEncoder().encodeToString(generated())); put("require_transparency", true)
    }
    private suspend fun imported(runtime: WorkspaceRuntime<RigPreviewModel>, reference: String? = null) =
        execute(runtime, "asset_import_png", importArguments(reference ?: prepared(runtime))).metadata.text("assetId")
    private fun files(): Set<String> = if (!Files.exists(temporary.resolve("store"))) emptySet() else
        Files.walk(temporary.resolve("store")).use { paths -> paths.filter(Files::isRegularFile).map { temporary.relativize(it).toString() }.toList().toSet() }

    @Test fun allFourWritesAdvanceAuxiliaryStateWithoutRigHistoryAndRepeatedImportIsNoop() = runBlocking<Unit> {
        val runtime = fixture(); val initial = runtime.capture(); val history = runtime.history()
        val reference = prepared(runtime)
        assertNotEquals(initial.state, runtime.capture().state)
        val asset = imported(runtime, reference); val beforeRepeat = runtime.capture(); val beforeFiles = files()
        val repeated = execute(runtime, "asset_import_png", importArguments(reference))
        assertEquals(asset, repeated.metadata.text("assetId")); assertEquals(beforeRepeat, runtime.capture()); assertEquals(beforeFiles, files())
        execute(runtime, "asset_register", buildJsonObject { put("asset_id", asset); put("mode", "frame") })
        assertNotEquals(beforeRepeat.state, runtime.capture().state)
        val reprocessed = execute(runtime, "asset_reprocess", buildJsonObject {
            put("asset_id", asset); putJsonObject("processing") { put("edge_width", 0) }
        })
        assertNotEquals(asset, reprocessed.metadata.text("asset_id"))
        assertEquals(history, runtime.history()); assertEquals(initial.revision, runtime.capture().revision)
        assertEquals(initial.document, runtime.capture().document); assertTrue(runtime.capture().dirty)
        store.validateAssetCatalog("project", WorkspaceAssetCatalog.read(runtime.capture().auxiliary)!!)
    }

    @Test fun failedRasterPreviewAfterRegistrationRecordCreationPublishesNothing() = runBlocking<Unit> {
        val runtime = fixture(); val asset = imported(runtime); val before = runtime.capture(); val beforeFiles = files()
        val failure = assertFailsWith<IllegalArgumentException> {
            execute(runtime, "asset_register", buildJsonObject {
                put("asset_id", asset); put("mode", "absolute")
                putJsonObject("transform") { put("x", .1); put("y", .1); put("scale_x", 256) }
            })
        }
        assertTrue(failure.message!!.contains("oversized")); assertEquals(before, runtime.capture()); assertEquals(beforeFiles, files())
    }

    @Test fun rejectedHostProjectionRollsBackOnlyNewFilesAndRetainsEarlierAssets() = runBlocking<Unit> {
        val runtime = fixture(); val asset = imported(runtime); val before = runtime.capture(); val beforeFiles = files()
        assertFailsWith<IllegalStateException> {
            execute(runtime, "asset_prepare_reference", prepareArguments()) { error("Projection rejected") }
        }
        assertEquals(before, runtime.capture()); assertEquals(beforeFiles, files())
        assertContentEquals(generated(), store.loadAsset("project", asset)!!.originalPng)
    }

    @Test fun staleAndSameProjectReloadRequestsFailBeforeImportFileParsing() = runBlocking<Unit> {
        val runtime = fixture(); val old = runtime.capture()
        runtime.install(old.state, old.projectId, old.document, old.model, runtime.history(), auxiliary = old.auxiliary)
        assertFailsWith<WorkspaceConflict> {
            WorkspaceAssetSessions(runtime).execute("project", old.state, store, "asset_import_png", JsonObject(emptyMap()))
        }
        assertTrue(files().isEmpty())
    }

    @Test fun conflictAfterPreparationRemovesPrivateStagingAndKeepsConcurrentAuxiliaryEdit() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val progress = WorkspaceJobContext { value, _ -> if (value == .6f) {
            runtime.updateAuxiliary("project", before.state, JsonObject(before.auxiliary + ("concurrent" to JsonPrimitive(true))))
        } }
        assertFailsWith<WorkspaceConflict> {
            withContext(progress) { execute(runtime, "asset_prepare_reference", prepareArguments()) }
        }
        assertEquals(JsonPrimitive(true), runtime.capture().auxiliary["concurrent"]); assertTrue(files().isEmpty())
        assertEquals(WorkspaceAssetCatalog(), WorkspaceAssetCatalog.read(runtime.capture().auxiliary))
    }

    @Test fun cancellationBeforeCommitPublishesNoFilesOrCatalog() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val operation = Job()
        val work = CoroutineScope(Dispatchers.Default + operation).async {
            withContext(WorkspaceJobContext { value, _ -> if (value == .6f) operation.cancel() }) {
                execute(runtime, "asset_prepare_reference", prepareArguments())
            }
        }
        assertFailsWith<CancellationException> { work.await() }; work.join()
        assertEquals(before, runtime.capture()); assertTrue(files().isEmpty())
    }

    @Test fun lateCancellationRetainsCommittedJobResultImagesAndState() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        WorkspaceJobs().use { jobs ->
            val job = jobs.start("asset_prepare_reference", "project", before.state,
                WorkspaceAssetResultSchemas.forOperation("asset_prepare_reference")) {
                val context = currentCoroutineContext(); val completion = context[WorkspaceJobCompletion]!!
                withContext(WorkspaceAssetJobExecution("asset_prepare_reference", completion)) {
                    execute(runtime, "asset_prepare_reference", prepareArguments()) { context.cancel() }
                    error("Cancellation should surface after the completion marker")
                }
            }
            val terminal = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status, terminal.error.toString())
            val output = terminal.result!!
            assertEquals(runtime.capture().state, output.data.text("state")); assertNotEquals(before.state, runtime.capture().state)
            assertEquals(2, output.images.size); assertContentEquals(output.images[0], jobs.get(job.id).result!!.images[0])
            assertEquals(output.data, jobs.cancel(job.id).result!!.data)
        }
    }

    @Test fun frozenLegacyAndOwnedQueriesExcludeLaterRegistrationRecords() = runBlocking<Unit> {
        val runtime = fixture(legacy = true)
        val api = WorkspaceAssetWorkflow("project", runtime.capture().revision, runtime.capture().document, store, WorkspacePngAssetStore())
        val reference = api.prepare(prepareArguments()).metadata.text("id")
        val assetStore = WorkspacePngAssetStore(); val asset = assetStore.import(WorkspacePngImportRequest(generated(), reference,
            referenceId = reference), store.loadSpatial("project", reference)!!)
        store.persistAsset("project", assetStore.require(asset.id))
        val frozen = WorkspaceAssetSessions(runtime).captureWorkflow(store)
        api.register(buildJsonObject { put("asset_id", asset.id); put("mode", "frame") })
        assertTrue(frozen.inspect(asset.id).asset.details.getValue("registrations").jsonArray.isEmpty())
        assertEquals(JsonObject(emptyMap()), runtime.capture().auxiliary)
        val current = runtime.capture()
        runtime.updateAuxiliary("project", current.state, buildJsonObject { put("assetCatalog", store.existingAssetCatalog("project").encode()) })
        val owned = WorkspaceAssetSessions(runtime).captureWorkflow(store)
        execute(runtime, "asset_register", buildJsonObject { put("asset_id", asset.id); put("mode", "frame") })
        assertEquals(1, owned.inspect(asset.id).asset.details.getValue("registrations").jsonArray.size)
        assertEquals(2, WorkspaceAssetSessions(runtime).captureWorkflow(store).inspect(asset.id).asset.details.getValue("registrations").jsonArray.size)
    }

    @Test fun savedCatalogExcludesFutureAssetsAndReopensOriginalProcessedPixelsAndHistory() = runBlocking<Unit> {
        val runtime = fixture(); val asset = imported(runtime); val captured = runtime.capture()
        val save = ProjectSaveCapture("project", runtime.history(), JsonObject(emptyMap()), null, store, auxiliary = captured.auxiliary)
        val future = prepared(runtime)
        val target = temporary.resolve("captured.psd2live"); val repository = ProjectRepository()
        repository.save(save, target)
        repository.open(target).use { opened ->
            assertEquals(WorkspaceAssetCatalog.read(captured.auxiliary), WorkspaceAssetCatalog.read(opened.presentation))
            assertFailsWith<IllegalArgumentException> { opened.store.loadWorkflow("project", future) }
            val restored = opened.store.loadAsset("project", asset)!!; val original = store.loadAsset("project", asset)!!
            assertContentEquals(original.rgba, restored.rgba); assertContentEquals(original.originalPng, restored.originalPng)
            assertEquals(save.history.selections.map { it.node }, opened.history.state().selections.map { it.node })
            val visuals = Path.of("build/asset-session-visual"); Files.createDirectories(visuals)
            Files.write(visuals.resolve("authored.png"), original.preview().png); Files.write(visuals.resolve("reopened.png"), restored.preview().png)
        }
    }

    @Test fun pngHeaderBudgetIsCheckedBeforeDecodingPixelsAndMatteLoopsCheckCancellation() {
        val original = generated(); val huge = original.copyOf()
        ByteBuffer.wrap(huge).putInt(16, 100_000).putInt(20, 100_000)
        val checksum = CRC32().also { it.update(huge, 12, 17) }.value.toInt()
        ByteBuffer.wrap(huge).putInt(29, checksum)
        val failure = assertFailsWith<IllegalArgumentException> { WorkspacePngAssetStore().import(WorkspacePngImportRequest(huge, "view"),
            WorkspaceViewSpatialMetadata(pixelWidth = 16, pixelHeight = 16, canvasWidth = 32f, canvasHeight = 32f,
                requestedViewRect = Bounds(0f, 0f, 32f, 32f), viewRect = Bounds(0f, 0f, 32f, 32f),
                canvasUnitsPerPixelX = 2f, canvasUnitsPerPixelY = 2f)) }
        assertTrue(failure.message!!.contains("megapixels"))
        val matte = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB).also { image ->
            for (y in 0..255) for (x in 0..255) image.setRGB(x, y, if (x in 100..150 && y in 100..150) 0xffff0000.toInt() else 0xff11ccff.toInt())
        }
        var checks = 0
        assertFailsWith<CancellationException> { processGeneratedMatte(matte, "#11ccff", 16, JsonObject(emptyMap())) {
            if (++checks == 4) throw CancellationException("Cancelled matte flood")
        } }
        assertEquals(4, checks)
    }

    @Test fun catalogValidationRejectsForeignOrMissingRecordsAndLegacyCopyRebindsProjectIdentity() = runBlocking<Unit> {
        val runtime = fixture(); val reference = prepared(runtime); val asset = imported(runtime, reference)
        val catalog = WorkspaceAssetCatalog.read(runtime.capture().auxiliary)!!
        assertFailsWith<IllegalArgumentException> { store.validateAssetCatalog("project", catalog.copy(workflow = emptySet())) }
        assertFailsWith<IllegalArgumentException> { store.validateAssetCatalog("other", catalog) }
        val copy = WorkspaceStore(temporary.resolve("copied"))
        store.copyAuxiliary("project", copy.projectRoot("copy"), catalog, targetProjectId = "copy")
        copy.validateAssetCatalog("copy", catalog)
        assertEquals("copy", copy.loadWorkflow("copy", reference).text("project_id"))
        assertEquals("project", store.loadWorkflow("project", reference).text("project_id"))
        assertContentEquals(store.loadAsset("project", asset)!!.rgba, copy.loadAsset("copy", asset)!!.rgba)
        assertFailsWith<IllegalArgumentException> { WorkspaceAssetCatalog.read(buildJsonObject {
            putJsonObject("assetCatalog") { put("version", 1); put("assets", JsonArray(listOf(JsonPrimitive(asset), JsonPrimitive(asset)))); put("workflow", JsonArray(emptyList())) }
        }) }
    }

    @Test fun cancellationDuringFilePublicationRollsBackThePublishedPrefixAndCleanupRejectsOtherPaths() {
        val storage = store
        val records = listOf("first", "second").associateWith { id -> buildJsonObject { put("id", id) } }
        storage.stageAssets("project", emptyList(), records, emptyMap()).use { stage ->
            var checks = 0
            assertFailsWith<CancellationException> { stage.publish({
                if (++checks == 2) {
                    Files.list(storage.projectRoot("project").resolve("workflow")).use { assertEquals(1, it.count()) }
                    throw CancellationException("Cancel partial publication")
                }
            }) { error("Must not reach publication callback") } }
            Files.list(storage.projectRoot("project").resolve("workflow")).use { assertEquals(0, it.count()) }
        }
        assertTrue(files().isEmpty())
        val unrelated = temporary.resolve("unrelated"); Files.createDirectories(unrelated); Files.writeString(unrelated.resolve("keep"), "keep")
        assertFailsWith<IllegalArgumentException> { storage.deleteAssetStage(unrelated, "project") }
        assertEquals("keep", Files.readString(unrelated.resolve("keep")))
    }
}
