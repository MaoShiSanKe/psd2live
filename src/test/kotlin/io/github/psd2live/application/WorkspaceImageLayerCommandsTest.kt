package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.bmp.BmpCodec
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceImageLayerCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val initial = simulationFixture(runtime)
        val document = initial.document.copy(settings = JsonObject(initial.document.settings + ("meshOnly" to JsonPrimitive(false))))
        val root = runtime.install(initial.state, initial.projectId, document, builder.build(document))
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Existing driving forms", simulationDrivingEdits(root), MutationAuthor.USER)
        return runtime
    }
    private fun image(name: String, bmp: Boolean = false): Path {
        val rgba = ByteArray(12 * 72 * 4)
        for (y in 2..69) for (x in 2..9) {
            val i = (y * 12 + x) * 4
            rgba[i] = 255.toByte(); rgba[i + 1] = 128.toByte(); rgba[i + 3] = 255.toByte()
        }
        val raster = RasterImage(12, 72, rgba)
        return temporary.resolve(name).also { Files.write(it, if (bmp) BmpCodec.write(raster) else PngCodec.write(raster)) }
    }
    private suspend fun import(runtime: WorkspaceRuntime<RigPreviewModel>, paths: List<Path>, parent: String? = null,
                               project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }) =
        runtime.capture().let { WorkspaceImageLayerCommands(runtime).importImages(it.projectId, it.state, paths, parent,
            "Imported image files", MutationAuthor.USER, project) }
    private fun unchanged(before: RigPreviewModel, after: RigPreviewModel) {
        for (value in listOf(-30f, 0f, 30f)) {
            val evaluator = CpuDeformationEvaluator()
            val pose = mapOf(ParameterId("Drive") to value)
            val old = evaluator.evaluate(before.rig.puppet, pose).worldPositions
            val next = evaluator.evaluate(after.rig.puppet, pose).worldPositions
            old.forEach { (id, geometry) -> assertContentEquals(geometry, next[id], "Changed ${id.raw} at $value") }
        }
    }

    @Test fun completePngAndBmpBatchCommitsOnceAndPreservesPriorMotionAndRepeatedNames() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val result = import(runtime, listOf(image("Decoration.png"), image("Decoration.bmp", true)))
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
        assertEquals(2, result.mutation.affectedLayerIds.size)
        assertEquals(2, result.mutation.affectedObjectIds.count { it.startsWith("mesh:") })
        assertEquals("user", runtime.history().selections.last().node.actor)
        unchanged(before.model, result.commit.capture.model)
        val second = import(runtime, listOf(image("Decoration.png")))
        unchanged(result.commit.capture.model, second.commit.capture.model)
        unchanged(second.commit.capture.model, builder.build(second.commit.capture.document))
        assertEquals(before.document, runtime.history().selections.single { it.node.id == before.historyHead }.snapshot)
        result.mutation.affectedLayerIds.forEach { id ->
            assertTrue(id in second.commit.capture.document.parentOverrides)
            assertNull(second.commit.capture.document.parentOverrides[id])
        }
    }

    @Test fun invalidLaterFileAndProjectionRejectionPublishNoPrefix() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val valid = image("first.png"); val bad = temporary.resolve("broken.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { import(runtime, listOf(valid, bad)) }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<IllegalStateException> { import(runtime, listOf(valid)) { _, _, _ -> error("Projection failed") } }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun staleStateWinsBeforeInvalidPathsOrParentParsing() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val changed = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("changed", true) })
        assertFailsWith<WorkspaceConflict> { WorkspaceImageLayerCommands(runtime).importImages(before.projectId, before.state,
            listOf(Path.of("missing.png")), "missing-parent", "Stale import", MutationAuthor.USER) }
        assertEquals(changed, runtime.capture())
    }

    @Test fun headerBudgetAndTransparentContentAreRejectedBeforeCommit() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val enormous = image("large.png")
        val bytes = Files.readAllBytes(enormous)
        ByteBuffer.wrap(bytes).putInt(16, 1_000_000).putInt(20, 1_000_000)
        Files.write(enormous, bytes)
        val failure = assertFailsWith<IllegalArgumentException> { import(runtime, listOf(enormous)) }
        assertTrue(failure.message!!.contains("16 megapixels"))
        val transparent = temporary.resolve("transparent.png").also { Files.write(it, PngCodec.write(RasterImage(1, 1, ByteArray(4)))) }
        assertFailsWith<IllegalStateException> { import(runtime, listOf(transparent)) }
        assertEquals(before, runtime.capture())
    }

    @Test fun successfulJobKeepsTheExactCommittedHandlesAfterLateCancellation() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val file = image("Decoration.png")
        WorkspaceJobs().use { jobs ->
            val job = jobs.start(WorkspaceImageLayerCommands.OP, before.projectId, before.state,
                WorkspaceJobResultSchemas.result(WorkspaceImageLayerCommands.OP)) {
                val context = currentCoroutineContext()
                withContext(WorkspaceLayerJobExecution(WorkspaceImageLayerCommands.OP, context[WorkspaceJobCompletion]!!)) {
                    import(runtime, listOf(file)) { _, _, _ -> context.cancel() }
                    error("Cancelled after commit")
                }
            }
            val terminal = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status, terminal.error.toString())
            val result = terminal.result!!.data
            assertEquals(runtime.capture().state, result.text("state"))
            assertEquals(1, result.getValue("affectedLayerIds").jsonArray.size)
            assertTrue(result.getValue("affectedObjectIds").jsonArray.any { it.jsonPrimitive.content.startsWith("mesh:") })
            assertEquals(result, jobs.cancel(job.id).result!!.data)
        }
    }

    @Test fun savedImportedPixelsAndIdentityReplayAfterTheOriginalFilesAreRemoved() = runBlocking<Unit> {
        val runtime = fixture(); val file = image("Decoration.png"); import(runtime, listOf(file))
        val before = runtime.capture(); val store = WorkspaceStore(temporary.resolve("store"))
        val repository = ProjectRepository(); val path = temporary.resolve("images.psd2live")
        repository.save(ProjectSaveCapture(before.projectId, runtime.history(), JsonObject(emptyMap()), null, store,
            auxiliary = before.auxiliary), path)
        Files.delete(file)
        repository.open(path).use { opened ->
            val document = opened.history.head().snapshot
            assertEquals(before.revision, WorkspaceRevisions.of(document))
            unchanged(before.model, builder.build(document))
            val added = document.source.layers.single { it.id.raw.startsWith("import:") }
            assertContentEquals(before.document.source.layers.single { it.id == added.id }.raster.rgba, added.raster.rgba)
        }
    }

    @Test fun importedModelsReceiveDurableNewMeshesWithoutChangingTheImportedArtwork() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val file = writeCmo3Fixture(temporary.resolve("model.cmo3"), "original")
        val before = WorkspaceCmo3Importer(runtime).import(file, Cmo3ImportMode.NEW, null, runtime.state.value.state,
            MutationAuthor.USER, initialConfig = PipelineConfig(atlasSize = 256)).capture
        val result = import(runtime, listOf(image("Addition.png")))
        assertEquals(2, result.commit.capture.model.rig.puppet.drawables.size)
        assertEquals(1, result.mutation.affectedObjectIds.count { it.startsWith("mesh:") })
        unchanged(before.model, result.commit.capture.model)
        validateRegisteredNeutral(result.commit.capture.model, result.mutation.affectedLayerIds.toSet())
        unchanged(result.commit.capture.model, builder.build(result.commit.capture.document))
    }

    @Test fun anAuthoredParentReceivesCanvasAlignedMeshCreationInsteadOfGeneratedFrameLookup() = runBlocking<Unit> {
        val runtime = fixture(); val initial = runtime.capture()
        val mesh = initial.model.rig.puppet.drawables.single().id.raw
        val createdParent = WorkspaceDocumentCommands(runtime).execute(initial.projectId, initial.state, "Parent", listOf(
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                put("id", "custom-parent"); put("name", "Custom parent"); put("rows", 2); put("columns", 2)
                put("meshes", buildJsonArray { add(mesh) })
            })), MutationAuthor.USER).capture
        val cp = (createdParent.model.rig.puppet.deformers.single { it.id.raw == "custom-parent" } as org.umamo.runtime.model.Deformer.Warp)
            .geometryGrid!!.cells.first().form.controlPoints
        val parent = WorkspaceDocumentCommands(runtime).execute(createdParent.projectId, createdParent.state, "Parent motion", listOf(
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for ((value, shift) in listOf(-30f to -4f, 30f to 4f)) add(buildJsonObject {
                    put("op", "set"); put("target", "warp:custom-parent"); putJsonObject("key") { put("Drive", value) }
                    putJsonObject("geometry") { put("controlPoints", JsonArray(cp.mapIndexed { i, coordinate -> JsonPrimitive(coordinate + if (i % 2 == 0) shift else 0f) })) }
                })
            } })), MutationAuthor.USER).capture
        val result = import(runtime, listOf(image("Addition.png")), "custom-parent")
        unchanged(parent.model, result.commit.capture.model)
        val added = result.commit.capture.model.rig.puppet.drawables.single { it.id.raw != mesh }
        assertEquals("custom-parent", added.parentDeformerId!!.raw)
        validateRegisteredNeutral(result.commit.capture.model, result.mutation.affectedLayerIds.toSet())
        unchanged(result.commit.capture.model, builder.build(result.commit.capture.document))
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(result.commit.capture.model.rig.puppet, emptyMap()).worldPositions.getValue(added.id)
        val moved = evaluator.evaluate(result.commit.capture.model.rig.puppet, mapOf(ParameterId("Drive") to 30f)).worldPositions.getValue(added.id)
        assertTrue(added.geometryGrid?.axes.orEmpty().isEmpty())
        assertTrue(rest.indices.filter { it % 2 == 0 }.all { moved[it] - rest[it] > 0.1f }, "New mesh must inherit the parent motion")
        val placement = WorkspaceImagePlacementCommands(runtime).execute(result.commit.capture.projectId, result.commit.capture.state,
            WorkspaceImageBounds(result.mutation.affectedLayerIds.single(), 18f, 20f, 12f, 16f).operation(), "Place in parent", MutationAuthor.USER).commit.capture
        unchanged(parent.model, placement.model)
        validateRegisteredNeutral(placement.model, result.mutation.affectedLayerIds.toSet())
        val regenerated = WorkspaceGenerationCommands(runtime).execute(placement.projectId, placement.state,
            WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("meshSpacing", 16) } }),
            "Regenerate mesh", MutationAuthor.USER).commit.capture
        assertEquals(2, regenerated.model.rig.puppet.drawables.size)
        val relocated = regenerated.model.rig.puppet.drawables.single { it.id == added.id }
        assertEquals(added.parentDeformerId, relocated.parentDeformerId)
        validateRegisteredNeutral(regenerated.model, result.mutation.affectedLayerIds.toSet())
        unchanged(regenerated.model, builder.build(regenerated.document))
        val relocatedRest = evaluator.evaluate(regenerated.model.rig.puppet, emptyMap()).worldPositions.getValue(added.id)
        val relocatedMoved = evaluator.evaluate(regenerated.model.rig.puppet, mapOf(ParameterId("Drive") to 30f)).worldPositions.getValue(added.id)
        assertTrue(relocatedRest.indices.filter { it % 2 == 0 }.all { relocatedMoved[it] - relocatedRest[it] > 0.1f })
        val second = import(runtime, listOf(image("Another addition.png")), "custom-parent").commit.capture
        val single = WorkspaceGenerationCommands(runtime).execute(second.projectId, second.state,
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", result.mutation.affectedLayerIds.single()); putJsonObject("changes") { put("outerMargin", 2) }
            }), "Rebuild one imported mesh", MutationAuthor.USER).commit.capture
        assertEquals(3, single.model.rig.puppet.drawables.size)
        validateRegisteredNeutral(single.model, result.mutation.affectedLayerIds.toSet())
        unchanged(single.model, builder.build(single.document))
    }
}
