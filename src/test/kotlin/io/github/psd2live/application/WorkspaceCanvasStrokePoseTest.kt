package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCanvasStrokePoseTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val shape = ParameterId("Shape")
    private val simulated = ParameterId("ParamSimdetached_1")

    private class Host(private val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author)
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }

    /** The generated mode changes only the second mesh, while the stroke edits the first. */
    private suspend fun fixture(): Pair<WorkspaceRuntime<RigPreviewModel>, String> {
        fun layer(id: String, x: Int, order: Int) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster,
            true, order, LayerBounds(x, 8, 32, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(32, 48, ByteArray(32 * 48 * 4) { if (it % 4 == 3) -1 else (70 + order * 40).toByte() }), null, null, false)
        val source = WorkspaceSourceArt(104, 64, listOf(layer("a", 8, 0), layer("b", 64, 1)), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshOnly = true, meshSpacing = 16, generateDeformers = false,
            generatePhysics = false, exportMoc3 = false)
        val original = WorkspaceDocument(source, emptyMap(), emptySet(), source.layers.associate {
            it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS)
        }, emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val base = builder.build(original).rig.puppet
        val first = base.drawables.first()
        val second = base.drawables.last()
        val count = second.mesh!!.vertexCount
        val offsets = listOf(-4f, 0f, 4f).map { dx -> FloatArray(count * 2) { if (it % 2 == 0) dx else 0f } }
        val baked = SimBakeResult("synthetic-mode", mapOf(second.id.raw to count), emptyList(), listOf(
            SimBakedMode(SimBakedAxis(simulated.raw, floatArrayOf(-30f, 0f, 30f), mapOf(second.id.raw to offsets)), 4f, 1f)))
        val overlay = RigEditOverlay(parameterEdits = listOf(RigParameterEdit(shape.raw, "Shape", 0f, 1f, 0f, created = true)),
            keyformSetEdits = listOf(RigKeyformSetEdit(RigTargetRef(RigTargetKind.ART_MESH, first.id.raw), mapOf(shape.raw to 1f),
                RigKeyformGeometryEdit(positionDeltas = List(first.mesh!!.positions.size) { if (it % 2 == 0) 2f else 0f }))),
            simEdits = listOf(RigSimEdit("detached", "Detached", SimKind.HAIR, listOf(second.id.raw), modes = 1,
                keys = 3, autoBake = false, blendShapes = false, bake = baked)))
        val document = original.copy(rigEdits = overlay)
        val runtime = WorkspaceRuntime<RigPreviewModel>(rebuild = { builder.build(it) })
        runtime.install(runtime.state.value.state, "stroke-pose", document, builder.build(document))
        return runtime to first.id.raw
    }

    private fun stroke(model: PuppetModel, target: String, mode: CanvasDeformStroke.Mode, value: Float): WorkspaceDocumentOperation {
        val pose = mapOf(shape.raw to 1f, simulated.raw to value)
        val points = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(target))
        val start = CanvasBrushPoint(points[0], -points[1])
        val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.BRUSH, mode,
            listOf(CanvasDeformStroke.Target("mesh", target, mapOf(shape.raw to 1f))), pose,
            CanvasBrushTip(96f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 0.5f, false)
        return WorkspaceCanvasDeformEdits.operation(request, listOf(CanvasDeformStroke.Sample(start),
            CanvasDeformStroke.Sample(start + CanvasBrushPoint(6f, 4f))))
    }

    private fun request(capture: WorkspaceCapture<RigPreviewModel>, operation: WorkspaceDocumentOperation, id: String) =
        JsonObject(operation.request + buildJsonObject {
            put("state", capture.state); put("project_id", capture.projectId); put("request_id", id)
        })

    private fun equalPoses(expected: PuppetModel, actual: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        for (shapeValue in listOf(0f, 0.5f, 1f)) for (modeValue in listOf(0f, 12.5f)) {
            val pose = mapOf(shape to shapeValue, simulated to modeValue)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            assertEquals(a.worldPositions.keys, b.worldPositions.keys)
            a.worldPositions.forEach { (id, points) ->
                val other = b.worldPositions.getValue(id)
                assertEquals(points.size, other.size)
                points.indices.forEach { assertEquals(points[it], other[it], 0.002f, "${id.raw} at $shapeValue / $modeValue") }
            }
        }
    }

    @Test fun detachedSimulationModesDoNotEnterStrokeHistoryAndAllAuthoringPosesSurviveReplayArchiveAndExport() = runBlocking<Unit> {
        for (mode in CanvasDeformStroke.Mode.entries) for (modeValue in listOf(0f, 12.5f)) {
            val (runtime, target) = fixture()
            val before = runtime.capture()
            val historySize = runtime.history().selections.size
            val operation = stroke(before.model.rig.puppet, target, mode, modeValue)
            assertTrue(before.model.rig.puppet.parameters.any { it.id == simulated })
            val evaluator = CpuDeformationEvaluator()
            val rest = evaluator.evaluate(before.model.rig.puppet, mapOf(shape to 1f, simulated to 0f))
            val moved = evaluator.evaluate(before.model.rig.puppet, mapOf(shape to 1f, simulated to 12.5f))
            val other = before.model.rig.puppet.drawables.last().id
            assertFalse(rest.worldPositions.getValue(other).contentEquals(moved.worldPositions.getValue(other)))
            WorkspaceOperations(Host(runtime)).use { operations ->
                val invalidStroke = operation.request.getValue("stroke").jsonObject.let { stroke ->
                    JsonObject(stroke + ("pose" to JsonObject(stroke.getValue("pose").jsonObject + ("Unknown" to JsonPrimitive(0f)))))
                }
                assertFailsWith<IllegalArgumentException> {
                    operations.registry.invoke(operation.operation, request(before, WorkspaceDocumentOperation(operation.operation,
                        buildJsonObject { put("stroke", invalidStroke) }), "invalid"), WorkspaceOperationContext(MutationAuthor.AGENT))
                }
                assertEquals(before, runtime.capture()); assertEquals(historySize, runtime.history().selections.size)
                operations.registry.invoke(operation.operation, request(before, operation, "stroke"), WorkspaceOperationContext(MutationAuthor.AGENT))
            }
            val after = runtime.capture()
            assertEquals(historySize + 1, runtime.history().selections.size)
            assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
            val recorded = after.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == "canvas_geometry" }
            assertFalse(simulated.raw in recorded.getValue("pose").jsonObject)
            assertEquals(1f, recorded.getValue("pose").jsonObject.getValue(shape.raw).jsonPrimitive.float)
            assertFalse(evaluator.evaluate(before.model.rig.puppet, mapOf(shape to 1f)).worldPositions.getValue(DrawableId(target))
                .contentEquals(evaluator.evaluate(after.model.rig.puppet, mapOf(shape to 1f)).worldPositions.getValue(DrawableId(target))))
            equalPoses(after.model.rig.puppet, builder.build(after.document).rig.puppet)
            val label = "${mode.name.lowercase()}-$modeValue"
            val archive = temporary.resolve("$label.psd2live")
            ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store-$label"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            equalPoses(after.model.rig.puppet, reopened.rig.puppet)
            fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "stroke-pose",
                mapOf(shape.raw to 1f, simulated.raw to modeValue), model.rig.layerIdByDrawableId.values.toSet(), emptySet(),
                WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 104f, 64f)), WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            assertContentEquals(render(after.model), render(reopened))
            val exported = PSD2LivePipeline().run(after.document.source, "stroke-pose", temporary.resolve("export-$label"),
                after.document.config().copy(exportMoc3 = false)).exportedFiles
            val cmo = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            equalPoses(after.model.rig.puppet, cmo)
            runtime.checkout(after.projectId, after.state, before.historyHead)
            assertEquals(before.document, runtime.capture().document)
            runtime.checkout(after.projectId, runtime.capture().state, after.historyHead)
            equalPoses(after.model.rig.puppet, runtime.capture().model.rig.puppet)
        }
    }
}
