package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceSimulationIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)

    @Test fun mcpSimulationBatchBakesReplaysSavesAndExportsTheSameGeometryAndPhysics() = runBlocking {
        val image = BufferedImage(12, 72, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xff506e8c.toInt())
        val png = temporary.resolve("strip.png")
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = false, meshSpacing = 8,
                atlasSize = 256, generatePhysics = false, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 64); put("height", 96)
                    putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "Strip"); put("role", "objects") }) }
                })
                val before = workspace.snapshot()
                val original = assertNotNull(workspace.currentPuppet())
                val edits = simulationDrivingEdits(original) + simulationPut(original) + WorkspaceDocumentOperation("simulation_bake",
                    buildJsonObject { put("id", "sway") })
                val request = buildJsonObject {
                    put("request_id", "simulation-batch"); put("project_id", before.projectId); put("state", before.state)
                    put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } }))
                }
                WorkspaceOperations(workspace).use { operations ->
                    suspend fun apply(): JsonObject {
                        val started = operations.registry.invoke("workspace_apply_edits", request, agent).data
                        val job = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, agent).data
                        assertEquals("completed", job.getValue("status").jsonPrimitive.content, job.toString())
                        return job.getValue("result").jsonObject
                    }
                    val result = apply()
                    assertEquals(result, apply())
                    val after = workspace.snapshot()
                    val baked = assertNotNull(workspace.listSimulations().single().bake)
                    assertTrue(baked.modes.isNotEmpty())
                    assertTrue(workspace.projectSettings().getValue("generatePhysics").jsonPrimitive.boolean)
                    assertEquals(2, workspace.history().nodes.size)
                    assertEquals("agent", workspace.history().nodes.last().actor)
                    assertEquals(after.state, result.getValue("state").jsonPrimitive.content)
                    val puppet = assertNotNull(workspace.currentPuppet())
                    val parameter = puppet.parameters.single { it.id.raw == baked.parameters.first() }
                    val view = WorkspaceModelViewRequest(parameters = mapOf(parameter.id.raw to parameter.max),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(-64f, -32f, 192f, 224f)), output = WorkspaceViewOutputSpec(256))
                    val rendered = workspace.renderModel(view).png
                    val archive = temporary.resolve("simulation.psd2live")
                    val controller = ProjectController(vm)
                    controller.save(workspace, archive)
                    controller.open(workspace, archive)
                    assertEquals(after.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
                    assertNotEquals(after.state, workspace.snapshot().state)
                    assertEquals(baked, workspace.listSimulations().single().bake)
                    val reopened = workspace.renderModel(view).png
                    val visual = Files.createDirectories(Path.of("build/simulation-command-visual"))
                    Files.write(visual.resolve("before-reopen.png"), rendered)
                    Files.write(visual.resolve("after-reopen.png"), reopened)
                    val reopenedPuppet = assertNotNull(workspace.currentPuppet())
                    assertEquals(puppet.parameters, reopenedPuppet.parameters)
                    assertTrue(puppet.drawables.first().mesh!!.positions.contentEquals(reopenedPuppet.drawables.first().mesh!!.positions), "Source mesh positions changed on reopen")
                    val grid = assertNotNull(puppet.drawables.first().geometryGrid)
                    val reopenedGrid = assertNotNull(reopenedPuppet.drawables.first().geometryGrid)
                    assertEquals(grid.axes.map { it.parameterId to it.keys.toList() }, reopenedGrid.axes.map { it.parameterId to it.keys.toList() })
                    grid.cellsByLinearIndex.forEach { (index, cell) ->
                        assertTrue(cell.form.positionDeltas.contentEquals(reopenedGrid.cellsByLinearIndex.getValue(index).form.positionDeltas), "Keyform $index changed on reopen")
                    }
                    assertTrue(rendered.contentEquals(reopened), "Rendered simulation pose changed on reopen")
                    val exported = workspace.exportModel(workspace.snapshot().state, temporary.resolve("export").toString())
                    val files = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    val cmo3 = files.single { it.toString().endsWith(".cmo3") }
                    val imported = Cmo3ModelImport.read(Files.readAllBytes(cmo3))
                    val readBack = imported.puppet
                    assertTrue(readBack.parameters.any { it.id == parameter.id })
                    assertTrue(readBack.drawables.first().geometryGrid!!.axes.any { it.parameterId == parameter.id })
                    assertTrue(baked.pendulums.isNotEmpty())
                    for (pendulum in baked.pendulums) {
                        val exportedPhysics = imported.physics.single { it.id == pendulum.id }
                        assertEquals(pendulum.outputs, exportedPhysics.outputs)
                        assertEquals(pendulum.inputs, exportedPhysics.inputs)
                        assertEquals(pendulum.segments, exportedPhysics.segments)
                    }
                    workspace.checkoutHistory(before.historyHeadNodeId!!, MutationAuthor.USER)
                    assertTrue(workspace.listSimulations().isEmpty())
                    assertTrue(workspace.currentPuppet()!!.parameters.none { it.id == parameter.id })
                    workspace.checkoutHistory(after.historyHeadNodeId!!, MutationAuthor.USER)
                    assertEquals(baked, workspace.listSimulations().single().bake)
                    assertContentEquals(rendered, workspace.renderModel(view).png)
                    suspend fun single(id: String, fields: JsonObject, document: Boolean = true): JsonObject {
                        val definition = operations.registry.definition(id)
                        assertTrue(definition.jobBacked)
                        assertEquals(document, definition.batchable)
                        val input = JsonObject(fields + buildJsonObject {
                            put("request_id", id); put("project_id", workspace.snapshot().projectId); put("state", workspace.snapshot().state)
                        })
                        val started = operations.registry.invoke(id, input, agent).data
                        assertEquals(started.getValue("id"), operations.registry.invoke(id, input, agent).data.getValue("id"))
                        val job = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, agent).data
                        assertEquals("completed", job.getValue("status").jsonPrimitive.content, job.toString())
                        return job.getValue("result").jsonObject
                    }
                    val noop = single("simulation_put", simulationPut(workspace.currentPuppet()!!).request)
                    assertFalse(noop.getValue("applied").jsonPrimitive.boolean)
                    single("simulation_clear_bake", buildJsonObject { put("id", "sway") })
                    assertNull(workspace.listSimulations().single().bake)
                    val fresh = single("simulation_bake", buildJsonObject { put("id", "sway") })
                    assertTrue(fresh.getValue("modes").jsonArray.isNotEmpty())
                    val freshView = workspace.renderModel(view).png
                    controller.save(workspace, archive); controller.open(workspace, archive)
                    assertContentEquals(freshView, workspace.renderModel(view).png)
                    val sampledState = workspace.snapshot()
                    val sampledHistory = workspace.history()
                    val simulationReport = single("simulation_simulate", buildJsonObject {
                        put("id", "sway"); put("hold", 0.1); put("release", 0.1)
                    }, document = false)
                    assertTrue(simulationReport.getValue("particles").jsonPrimitive.int > 0)
                    assertTrue(simulationReport.getValue("phases").jsonArray.isNotEmpty())
                    val physicsReport = single("physics_simulate", buildJsonObject {
                        putJsonArray("ids") { baked.pendulums.forEach { add(it.id) } }
                        putJsonObject("inputs") { put("Drive", 30) }; put("duration", 0.2); put("hold", 0.1); put("samples", 3)
                    }, document = false)
                    assertTrue(physicsReport.getValue("outputs").jsonArray.isNotEmpty())
                    for (sample in listOf(simulationReport, physicsReport)) {
                        assertEquals(sampledState.state, sample.getValue("state").jsonPrimitive.content)
                        assertEquals(sampledState.revisionId, sample.getValue("revision").jsonPrimitive.content)
                        assertEquals(sampledState.projectId, sample.getValue("project_id").jsonPrimitive.content)
                    }
                    assertEquals(sampledState.state, workspace.snapshot().state)
                    assertEquals(sampledHistory, workspace.history())
                    assertContentEquals(freshView, workspace.renderModel(view).png)
                    single("simulation_delete", buildJsonObject { put("id", "sway") })
                    assertTrue(workspace.listSimulations().isEmpty())
                    single("model_apply_preset", buildJsonObject { put("preset", "classic_back_hair"); put("sway", false) })
                    assertFalse(workspace.projectSettings().getValue("physicsBackHair").jsonPrimitive.boolean)
                }
            }
        }
    }
}
