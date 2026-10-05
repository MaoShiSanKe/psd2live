package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceDrawOrderTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(private val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            return WorkspaceDocumentCommands.mutationResult(before,
                WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author), summary, edits)
        }
    }
    private fun edit(target: String, order: Float?) = WorkspaceDocumentOperation(WorkspaceDrawOrderEdits.OP, WorkspaceDrawOrderEdits.request(target, order))
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, edit: WorkspaceDocumentOperation, id: String) = JsonObject(edit.request + buildJsonObject {
        put("project_id", runtime.capture().projectId); put("state", runtime.capture().state); put("request_id", id)
    })
    private suspend fun fixture(imported: Boolean): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        if (imported) WorkspaceCmo3Importer(runtime).import(writeCmo3Fixture(temporary.resolve("input.cmo3"), "Card"),
            Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER, initialConfig = PipelineConfig(atlasSize = 256, exportMoc3 = false))
        else simulationFixture(runtime)
        val before = runtime.capture(); val mesh = before.model.rig.puppet.drawables.first()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Order animation", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Order"); put("name", "Order"); put("min", 0); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (value in listOf(0f, 1f)) add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Order", value) }
                    putJsonObject("channels") { put("drawOrder", 15f + value * 10f); put("opacity", 0.3f + value * 0.5f) }
                })
                add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Blend", 1) }
                    putJsonObject("channels") { put("drawOrder", 40); put("opacity", 0.9f) }
                })
            } }),
        ), MutationAuthor.USER)
        return runtime
    }
    private fun assertPose(expected: PuppetModel, actual: PuppetModel, target: DrawableId, override: Float? = null) {
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, 0.4f, 1f)) for (blend in listOf(0f, 0.6f, 1f)) {
            val pose = mapOf(ParameterId("Order") to value, ParameterId("Blend") to blend)
            val a = evaluator.evaluate(expected, pose)
            val b = evaluator.evaluate(actual, pose)
            a.worldPositions.forEach { (id, points) ->
                assertEquals(points.size, b.worldPositions.getValue(id).size)
                points.indices.forEach { assertEquals(points[it], b.worldPositions.getValue(id)[it], 0.001f) }
                assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), 0.00001f)
                assertEquals(if (id == target && override != null) override else a.drawOrder.getValue(id), b.drawOrder.getValue(id), 0.00001f)
            }
        }
    }

    @Test fun publicLayerOrderAndResetRetainAuthoredAnimationAcrossReplayArchiveAndBothExports() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val runtime = fixture(imported); val before = runtime.capture(); val original = before.model.rig.puppet
            val target = original.drawables.first().id
            WorkspaceOperations(Host(runtime)).use { operations ->
                val operation = edit("mesh:${target.raw}", 333f); val request = input(runtime, operation, "set")
                val result = operations.registry.invoke(operation.operation, request, agent).data
                assertEquals(result, operations.registry.invoke(operation.operation, request, agent).data)
                val changed = runtime.capture()
                assertEquals(before.document.rigEdits, changed.document.rigEdits)
                assertPose(original, changed.model.rig.puppet, target, 333f)
                assertPose(changed.model.rig.puppet, builder.build(changed.document).rig.puppet, target)
                val archive = temporary.resolve("order-$imported.psd2live")
                ProjectRepository().save(ProjectSaveCapture(changed.projectId, runtime.history(), JsonObject(emptyMap()), null,
                    WorkspaceStore(temporary.resolve("store-$imported"))), archive)
                val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
                assertPose(changed.model.rig.puppet, reopened.rig.puppet, target)
                val files = PSD2LivePipeline().run(changed.document.source, "order", temporary.resolve("export-$imported"),
                    changed.document.config().copy(exportMoc3 = true)).exportedFiles
                val cmo = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
                val moc = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null)
                listOf(cmo, moc).forEach { assertPose(changed.model.rig.puppet, it, target) }
                fun png(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "order", mapOf("Order" to 0.4f),
                    model.rig.layerIdByDrawableId.values.toSet(), emptySet(),
                    WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, model.analysis.source.widthPx.toFloat(), model.analysis.source.heightPx.toFloat())),
                    WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
                val pixels = png(changed.model); assertContentEquals(pixels, png(reopened))
                val image = javax.imageio.ImageIO.read(pixels.inputStream())
                assertTrue((0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) ushr 24 > 0 } } > 50)
                val visuals = Path.of("build/draw-order-visual/$imported"); Files.createDirectories(visuals)
                Files.write(visuals.resolve("before-reopen.png"), pixels); Files.write(visuals.resolve("after-reopen.png"), png(reopened))
                val layer = changed.model.rig.layerIdByDrawableId.getValue(target.raw)
                val reset = edit(layer, null)
                operations.registry.invoke(reset.operation, input(runtime, reset, "reset"), agent)
                assertPose(original, runtime.capture().model.rig.puppet, target)
                val nodes = runtime.history().selections.size
                val noOp = operations.registry.invoke(reset.operation, input(runtime, reset, "again"), agent).data
                assertFalse(noOp.getValue("applied").jsonPrimitive.boolean); assertEquals(nodes, runtime.history().selections.size)
                runtime.checkout(changed.projectId, runtime.capture().state, changed.historyHead)
                assertPose(original, runtime.capture().model.rig.puppet, target, 333f)
            }
        }
    }

    @Test fun invalidTargetsOrdersStaleRequestsAndFailedAtomicBatchesPublishNoPrefix() = runBlocking<Unit> {
        val runtime = fixture(false); val before = runtime.capture(); val history = runtime.history()
        val target = before.model.rig.puppet.drawables.first().id.raw
        WorkspaceOperations(Host(runtime)).use { operations ->
            for ((index, invalid) in listOf(edit("missing", 5f), edit(target, -1f), edit(target, 1001f)).withIndex()) {
                assertFailsWith<IllegalArgumentException> { operations.registry.invoke(invalid.operation, input(runtime, invalid, "bad-$index"), agent) }
                assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            }
            assertFailsWith<WorkspaceBatchEditException> {
                WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Invalid after order", listOf(edit(target, 500f), edit("missing", 1f)), MutationAuthor.AGENT)
            }
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            val request = input(runtime, edit(target, 500f), "stale")
            WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Other edit", listOf(edit(target, 10f)), MutationAuthor.USER)
            val current = runtime.capture()
            assertFailsWith<WorkspaceConflict> { operations.registry.invoke(WorkspaceDrawOrderEdits.OP, request, agent) }
            assertEquals(current, runtime.capture())
        }
    }

    @Test fun cancellingTheIsolatedRebuildKeepsTheOriginalOrderDocumentAndHistory() = runBlocking<Unit> {
        var block = false; val entered = CompletableDeferred<Unit>()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ document ->
            if (block && document.settings["drawOrderOverrides"]?.jsonObject?.isNotEmpty() == true) { entered.complete(Unit); awaitCancellation() }
            builder.build(document)
        })
        simulationFixture(runtime); val before = runtime.capture(); val history = runtime.history(); block = true
        val action = launch { WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Cancelled order",
            listOf(edit(before.model.rig.puppet.drawables.first().id.raw, 40f)), MutationAuthor.USER) }
        withTimeout(10000) { entered.await() }; action.cancelAndJoin()
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }
}
