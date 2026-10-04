package io.github.psd2live.agent

import io.github.psd2live.ui.state.DesktopWorkspace

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.application.WorkspaceHistoryPhysicsResultSchemas
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.application.WorkspaceOperationContext
import io.github.psd2live.application.WorkspaceJobResultSchemas
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.core.Bounds
import io.github.psd2live.ui.state.ProjectController
import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.application.validateOperationSchema

import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The panel's physics actions as MCP reaches them: import, fit, order and rate, through history and back. */
class PhysicsWorkspaceTest {
    @TempDir lateinit var temp: Path

    @Test fun importFitOrderAndRateCommitAndRestore() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 16); put("height", 16)
                    putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "decoration"); put("role", "objects") }) }
                })
                fun group(id: String, output: String) = RigPhysicsEdit(id, id,
                    listOf(PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X), PhysicsInput("ParamAngleZ", 60f, PhysicsSourceType.ANGLE)),
                    listOf(PhysicsOutput(output, 1, 0.3f)), listOf(PhysicsSegment(8f, 0.9f, 0.9f, 1.2f)))
                val file = temp.resolve("model.physics3.json")
                Files.writeString(file, Physics3Json.write(listOf(group("Ribbon", "ParamHairFront"), group("Bow", "ParamHairBack")), 30)!!)

                val (imported, report) = workspace.importPhysics(file.toString(), created.state!!)
                assertEquals(listOf("Ribbon", "Bow"), report.getValue("imported").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(30, workspace.physicsFps())
                assertEquals(listOf("Ribbon", "Bow"), workspace.listPhysics().filter { it.active }.map { it.id }.takeLast(2))

                for (hold in listOf(0f, 0.1f)) {
                    val response = workspace.simulatePhysics(buildJsonObject {
                        putJsonObject("inputs") { put("ParamAngleX", 20) }
                        put("hold", hold); put("duration", 0.1); put("samples", 2)
                    })
                    validateOperationSchema(response, WorkspaceHistoryPhysicsResultSchemas.forOperation("physics_simulate")!!)
                    assertTrue(response.getValue("outputs").jsonArray.all { row ->
                        row.jsonObject.getValue(if (hold == 0f) "while_held" else "after_release").jsonObject.isEmpty()
                    })
                }

                val fitted = workspace.fitPhysics("Ribbon", 1f, imported.state!!)
                val scale = workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale
                assertNotEquals(0.3f, scale)

                val configured = workspace.configurePhysics(listOf("Bow"), 120, fitted.state!!)
                assertEquals("Bow", workspace.listPhysics().first().id)
                assertEquals(120, workspace.physicsFps())
                assertFailsWith<IllegalArgumentException> { workspace.configurePhysics(null, 500, configured.state!!) }

                // History restores the order and rate with the groups.
                workspace.checkoutHistory(imported.historyNodeId, MutationAuthor.AGENT)
                assertEquals(30, workspace.physicsFps())
                assertEquals(0.3f, workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale)
                workspace.checkoutHistory(configured.historyNodeId, MutationAuthor.AGENT)
                assertEquals(120, workspace.physicsFps())
                assertEquals("Bow", workspace.listPhysics().first().id)
                assertEquals(scale, workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale)
            }
        }
    }

    @Test fun mcpPhysicsJobsPreserveDiagnosticsHistorySaveReopenAndExportedPendulums() = runBlocking<Unit> {
        val png = temp.resolve("ribbon.png")
        val image = BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..29) for (x in 2..13) image.setRGB(x, y, 0xff4b82a8.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
            vm.attachWorkspace(workspace)
            vm.setStateForTest(vm.state.value.copy(exportMoc3 = false))
            workspace.createArtwork(buildJsonObject {
                put("width", 32); put("height", 48)
                putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "Ribbon"); put("role", "objects") }) }
            })
            val group = RigPhysicsEdit("Ribbon", "Ribbon", listOf(PhysicsInput("ParamAngleX", 100f, PhysicsSourceType.X)),
                listOf(PhysicsOutput("ParamHairFront", 1, 0.3f)), listOf(PhysicsSegment(8f)))
            val physicsFile = temp.resolve("ribbon.physics3.json")
            Files.writeString(physicsFile, Physics3Json.write(listOf(group), 30)!!)
            val context = WorkspaceOperationContext(MutationAuthor.AGENT)
            WorkspaceOperations(workspace).use { operations ->
                suspend fun single(id: String, fields: JsonObject): JsonObject {
                    val definition = operations.registry.definition(id)
                    assertTrue(definition.jobBacked)
                    assertEquals(id != "physics_import", definition.batchable)
                    val before = workspace.snapshot()
                    val request = JsonObject(fields + buildJsonObject { put("request_id", "${id}-${before.state}"); put("project_id", before.projectId); put("state", before.state) })
                    val job = operations.registry.invoke(id, request, context).data
                    val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, context).data
                    assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                    assertEquals(job.getValue("id"), operations.registry.invoke(id, request, context).data.getValue("id"))
                    val result = terminal.getValue("result").jsonObject
                    validateOperationSchema(result, WorkspaceJobResultSchemas.result(id))
                    assertEquals(workspace.snapshot().state, result.getValue("state").jsonPrimitive.content)
                    return result
                }
                val imported = single("physics_import", buildJsonObject { put("path", physicsFile.toString()) })
                assertEquals(JsonArray(listOf(JsonPrimitive("Ribbon"))), imported.getValue("imported"))
                assertEquals(30, imported.getValue("fps").jsonPrimitive.int)
                val importedHead = workspace.snapshot().historyHeadNodeId!!
                single("physics_fit", buildJsonObject { put("id", "Ribbon"); put("target", 100) })
                val fitted = workspace.listPhysics().single { it.id == "Ribbon" }.setting
                assertNotEquals(group.outputs, fitted.outputs)
                single("physics_config", buildJsonObject { put("fps", 120); putJsonArray("order") { add("Ribbon") } })
                val nodes = workspace.history().nodes.size
                assertFalse(single("physics_config", buildJsonObject { put("fps", 120) }).getValue("applied").jsonPrimitive.boolean)
                assertEquals(nodes, workspace.history().nodes.size)
                val head = workspace.snapshot().historyHeadNodeId!!
                workspace.checkoutHistory(importedHead, MutationAuthor.USER)
                assertEquals(group.outputs, workspace.listPhysics().single { it.id == "Ribbon" }.setting.outputs)
                workspace.checkoutHistory(head, MutationAuthor.USER)
                assertEquals(fitted, workspace.listPhysics().single { it.id == "Ribbon" }.setting)
                val parameters = workspace.currentPuppet()!!.parameters.filter { it.id.raw in setOf("ParamAngleX", "ParamHairFront") }.associate { it.id.raw to it.max }
                val view = WorkspaceModelViewRequest(parameters = parameters, frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 48f)))
                val before = workspace.renderModel(view).png
                val controller = ProjectController(vm)
                val archive = temp.resolve("physics.psd2live")
                controller.save(workspace, archive); controller.open(workspace, archive)
                assertEquals(head, workspace.snapshot().historyHeadNodeId)
                assertEquals(fitted, workspace.listPhysics().single { it.id == "Ribbon" }.setting)
                assertEquals(120, workspace.physicsFps())
                val after = workspace.renderModel(view).png
                assertContentEquals(before, after)
                val visual = Files.createDirectories(Path.of("build/physics-command-visual"))
                Files.write(visual.resolve("before-reopen.png"), before); Files.write(visual.resolve("after-reopen.png"), after)
                val exported = workspace.exportModel(workspace.snapshot().state, temp.resolve("export").toString())
                val path = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }.single { it.toString().endsWith(".cmo3") }
                val readBack = Cmo3ModelImport.read(Files.readAllBytes(path))
                val restored = readBack.physics.single { it.id == "Ribbon" }
                assertEquals(fitted.inputs, restored.inputs); assertEquals(fitted.outputs, restored.outputs); assertEquals(fitted.segments, restored.segments)
                single("physics_put", buildJsonObject { put("id", "Ribbon"); put("enabled", false) })
                assertFalse(workspace.listPhysics().single { it.id == "Ribbon" }.active)
                single("physics_delete", buildJsonObject { put("id", "Ribbon") })
                assertTrue(workspace.listPhysics().none { it.id == "Ribbon" })
            }
        } }
    }
}
