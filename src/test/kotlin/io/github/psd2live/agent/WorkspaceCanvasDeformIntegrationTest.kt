package io.github.psd2live.agent

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceCanvasDeformIntegrationTest {
    @TempDir lateinit var temporary: Path
    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, WorkspaceOperations, List<String>) -> Unit) {
        val file = temporary.resolve("art.png"); val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 8..55) for (x in 8..55) image.setRGB(x, y, 0xff6699bb.toInt())
        ImageIO.write(image, "png", file.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, generateDeformers = false, atlasSize = 256, generatePhysics = false, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject { put("width", 64); put("height", 64)
                    putJsonArray("layers") { repeat(2) { add(buildJsonObject { put("path", file.toString()); put("name", "Art$it"); put("role", "objects") }) } } })
                val ids = workspace.currentPuppet()!!.drawables.map { it.id.raw }
                workspace.applyDocumentEdits(workspace.snapshot().state, "Prepare authored Warp and seam", listOf(
                    WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "Parent"); put("name", "Parent"); put("rows", 2); put("columns", 2)
                        putJsonArray("meshes") { ids.forEach { add(it) } } }),
                    WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Seam"); put("mesh_a", ids[0]); put("mesh_b", ids[1]); put("distance", 100) })
                ), MutationAuthor.USER)
                workspace.createParameter(WorkspaceCreateParameterRequest("Shape", workspace.snapshot().state, "Shape"))
                workspace.authorRig(workspace.snapshot().state, buildJsonArray {
                    workspace.currentPuppet()!!.drawables.forEach { drawable -> add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:${drawable.id.raw}"); putJsonObject("key") { put("Shape", 1) }
                        putJsonObject("geometry") { put("positionDeltas", JsonArray(List(drawable.mesh!!.positions.size) { JsonPrimitive(0.02f) })) }
                        putJsonObject("channels") { put("opacity", 0.7) }
                    }) }
                }, MutationAuthor.USER)
                workspace.setPreviewSession(buildJsonObject { put("state", workspace.snapshot().state); put("mode", "set"); putJsonObject("values") { put("Shape", 1) } })
                WorkspaceOperations(workspace).use { action(vm, workspace, it, created.affectedLayerIds) }
            }
        }
    }
    private fun equalGeometry(expected: PuppetModel, actual: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, 0.5f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to value)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            assertEquals(a.worldPositions.keys, b.worldPositions.keys)
            a.worldPositions.forEach { (id, points) -> points.indices.forEach { assertEquals(points[it], b.worldPositions.getValue(id)[it], 0.002f) } }
            a.opacity.forEach { (id, opacity) -> assertEquals(opacity, b.opacity.getValue(id), 0.0001f) }
        }
        expected.drawables.zip(actual.drawables).forEach { (old, fresh) -> assertContentEquals(old.mesh!!.uvs, fresh.mesh!!.uvs) }
    }
    private suspend fun settled(editor: CanvasEditor, vm: PSD2LiveViewModel) = withTimeout(10000) {
        while (editor.busy || vm.state.value.canvasEditBusy) delay(10)
        assertNull(editor.error); assertNull(vm.state.value.errorMessage)
    }
    private suspend fun publicStroke(operations: WorkspaceOperations, workspace: DesktopWorkspace, operation: WorkspaceDocumentOperation, id: String) {
        val before = workspace.snapshot()
        val request = JsonObject(operation.request + buildJsonObject { put("state", before.state); put("project_id", before.projectId); put("request_id", id) })
        val output = operations.registry.invoke(operation.operation, request, WorkspaceOperationContext(MutationAuthor.AGENT)).data
        assertEquals(output, operations.registry.invoke(operation.operation, request, WorkspaceOperationContext(MutationAuthor.AGENT)).data)
    }
    private fun screen(point: CanvasBrushPoint, viewport: CanvasViewport) = Offset(viewport.x(point.x).toFloat(), viewport.yFromWorld(-point.y).toFloat())

    @Test fun actualMultiMeshBrushSmoothInflateGesturesMatchPublicCanvasPixelsAtDifferentViewportScales() = runBlocking<Unit> {
        fixture { vm, workspace, operations, layers ->
            val baseline = workspace.snapshot().historyHeadNodeId!!
            for (tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) for (scale in listOf(0.75, 2.5)) {
                workspace.checkoutHistory(baseline, MutationAuthor.USER)
                val editor = vm.canvasEditor
                editor.hierarchyMode = EditHierarchyMode.EDIT; editor.tool = tool; editor.elementMode = 0
                vm.updateCanvasPresentation(editor.state.activeWorkspace.id, editor.state.activeCanvas.id, CanvasMode.EDIT) {
                    it.copy(selectedLayerId = layers.first(), selectedLayerIds = layers.toSet(), selectedDeformerId = null)
                }
                val source = workspace.currentPuppet()!!; val ids = source.drawables.map { it.id.raw }
                editor.selection = ids.associateWith { setOf(0) }; editor.radius = 48f; editor.strength = 0.4f
                editor.hardness = 0.5f; editor.connectedOnly = false; editor.brushFalloff = BrushFalloff.CONSTANT
                val world = CpuDeformationEvaluator().evaluate(source, editor.pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(source.drawables.first().id)
                val start = CanvasBrushPoint(world[0] - 3f, -world[1] - 2f)
                val samples = listOf(start, start + CanvasBrushPoint(6f, 3f), start + CanvasBrushPoint(10f, 7f)).map { CanvasDeformStroke.Sample(it) }
                val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.valueOf(tool.name), CanvasDeformStroke.Mode.EDIT,
                    ids.map { CanvasDeformStroke.Target("mesh", it, vertices = setOf(0)) }, editor.pose,
                    CanvasBrushTip(48f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 0.4f, false)
                val operation = WorkspaceCanvasDeformEdits.operation(request, samples)
                val viewport = CanvasViewport(scale, 43.0, 57.0, 64f, 64f)
                val before = workspace.snapshot(); val historySize = workspace.history().nodes.size
                assertTrue(editor.press(screen(start, viewport), viewport, false, false))
                samples.drop(1).forEach { editor.move(screen(it.point, viewport), viewport, false) }
                assertEquals(before.state, workspace.snapshot().state); assertEquals(historySize, workspace.history().nodes.size)
                val transient = assertNotNull(editor.preview); assertTrue(transient !== source)
                editor.release(); settled(editor, vm)
                val gui = workspace.currentPuppet()!!
                equalGeometry(transient, gui); assertEquals(historySize + 1, workspace.history().nodes.size)
                assertEquals("user", workspace.history().nodes.last().actor)
                workspace.checkoutHistory(baseline, MutationAuthor.USER)
                publicStroke(operations, workspace, operation, "$tool-$scale")
                equalGeometry(gui, workspace.currentPuppet()!!)
            }
        }
    }

    @Test fun actualWarpBrushCtrlAndEditPreserveChildrenAndCancellationPublishesNoPreviewGeometry() = runBlocking<Unit> {
        fixture { vm, workspace, operations, _ ->
            val baseline = workspace.snapshot().historyHeadNodeId!!
            for (mode in listOf(EditHierarchyMode.EDIT, EditHierarchyMode.DEFORM)) for (ctrl in listOf(false, true)) {
                workspace.checkoutHistory(baseline, MutationAuthor.USER)
                val editor = vm.canvasEditor; editor.hierarchyMode = mode; editor.tool = CanvasTool.BRUSH
                vm.updateCanvasPresentation(editor.state.activeWorkspace.id, editor.state.activeCanvas.id, CanvasMode.EDIT) {
                    it.copy(selectedDeformerId = "Parent", selectedLayerId = null, selectedLayerIds = emptySet())
                }
                editor.vertices = setOf(0); editor.radius = 48f; editor.strength = 0.5f
                editor.hardness = 0.5f; editor.connectedOnly = false; editor.brushFalloff = BrushFalloff.CONSTANT
                val source = workspace.currentPuppet()!!; val target = editor.target()!!
                val world = target.mapping.localToWorld(target.geometry.points)
                val start = CanvasBrushPoint(world[0], -world[1]); val end = start + CanvasBrushPoint(8f, 5f)
                val viewport = CanvasViewport(1.75, 19.0, 23.0, 64f, 64f)
                val before = workspace.snapshot(); val historySize = workspace.history().nodes.size
                editor.press(screen(start, viewport), viewport, false, false, ctrl)
                editor.move(screen(end, viewport), viewport, false, ctrl = ctrl)
                assertEquals(before.state, workspace.snapshot().state)
                editor.cancel(); editor.release()
                assertEquals(before.state, workspace.snapshot().state); assertEquals(historySize, workspace.history().nodes.size); assertNull(editor.preview)
                editor.press(screen(start, viewport), viewport, false, false, ctrl)
                editor.move(screen(end, viewport), viewport, false, ctrl = ctrl)
                editor.release(); settled(editor, vm)
                val gui = workspace.currentPuppet()!!
                if (mode == EditHierarchyMode.EDIT || ctrl) {
                    val evaluator = CpuDeformationEvaluator(); val old = evaluator.evaluate(source, emptyMap()); val next = evaluator.evaluate(gui, emptyMap())
                    old.worldPositions.forEach { (id, points) -> points.indices.forEach { assertEquals(points[it], next.worldPositions.getValue(id)[it], 0.02f) } }
                }
                val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.BRUSH, CanvasDeformStroke.Mode.valueOf(mode.name),
                    listOf(CanvasDeformStroke.Target("warp", "Parent", vertices = setOf(0))), editor.pose,
                    CanvasBrushTip(48f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 0.5f, false)
                val operation = WorkspaceCanvasDeformEdits.operation(request, listOf(CanvasDeformStroke.Sample(start), CanvasDeformStroke.Sample(end, preserveChildren = ctrl)))
                workspace.checkoutHistory(baseline, MutationAuthor.USER)
                publicStroke(operations, workspace, operation, "warp-$mode-$ctrl")
                equalGeometry(gui, workspace.currentPuppet()!!)
                val guiWarp = gui.deformers.single { it.id.raw == "Parent" } as Deformer.Warp
                val agentWarp = workspace.currentPuppet()!!.deformers.single { it.id.raw == "Parent" } as Deformer.Warp
                assertContentEquals(guiWarp.geometryGrid!!.cells.single().form.controlPoints, agentWarp.geometryGrid!!.cells.single().form.controlPoints)
            }
        }
    }
}
