package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspacePhysicsPanelIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiPhysicsIntentsAndPublicCommandsShareCandidatesHistoryArchiveAndExport() = runBlocking<Unit> {
        val artwork = temporary.resolve("ribbon.png")
        val pixels = BufferedImage(24, 40, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..36) for (x in 4..19) pixels.setRGB(x, y, 0xff568cab.toInt())
        ImageIO.write(pixels, "png", artwork.toFile())
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
            vm.attachWorkspace(workspace)
            vm.setStateForTest(vm.state.value.copy(exportMoc3 = true))
            workspace.createArtwork(buildJsonObject {
                put("width", 48); put("height", 64)
                putJsonArray("layers") { add(buildJsonObject { put("path", artwork.toString()); put("name", "Ribbon"); put("role", "objects") }) }
            })
            suspend fun gui(action: () -> Unit) {
                val nodes = workspace.history().nodes.size
                action()
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy && !it.editorDraftBusy } }
                assertNull(vm.state.value.errorMessage)
                assertEquals(nodes + 1, workspace.history().nodes.size)
                assertEquals("user", workspace.history().nodes.last().actor)
            }
            val group = RigPhysicsEdit("Ribbon", "Ribbon", listOf(PhysicsInput("ParamAngleX", type = PhysicsSourceType.X)),
                listOf(PhysicsOutput("ParamHairFront", scale = 0.3f)), listOf(PhysicsSegment(8f)),
                PhysicsNormalization(angleMin = -50f, angleMax = 50f))
            gui { vm.putPhysicsGroup(group) }
            val base = workspace.snapshot()
            val preset = PhysicsPresets.Preset(PhysicsPresets.Kind.INPUT, "Default inputs", group.inputs, PhysicsNormalization())
            gui { vm.applyPhysicsPreset(group.id, preset) }
            val guiSetting = workspace.listPhysics().single { it.id == group.id }.setting
            assertEquals(PhysicsNormalization(), guiSetting.normalization)
            workspace.checkoutHistory(base.historyHeadNodeId!!, MutationAuthor.USER)
            WorkspaceOperations(workspace).use { operations ->
                val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                suspend fun public(id: String, fields: JsonObject): JsonObject {
                    val capture = workspace.snapshot()
                    val input = JsonObject(fields + buildJsonObject {
                        put("request_id", "$id:${capture.state}"); put("project_id", capture.projectId); put("state", capture.state)
                    })
                    val job = operations.registry.invoke(id, input, agent).data
                    val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
                    assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                    assertEquals(job.getValue("id"), operations.registry.invoke(id, input, agent).data.getValue("id"))
                    return terminal.getValue("result").jsonObject
                }
                public("physics_put", buildJsonObject {
                    put("id", group.id); put("inputs", JsonArray(group.inputs.map { it.toJson() })); put("normalization", PhysicsNormalization().toJson())
                })
                assertEquals(guiSetting, workspace.listPhysics().single { it.id == group.id }.setting)
                val gestureState = workspace.snapshot().state; val gestureNodes = workspace.history().nodes.size
                vm.beginEditorGesture()
                vm.putPhysicsGroup(guiSetting.copy(name = "Draft"))
                vm.putPhysicsGroup(guiSetting.copy(name = "Final name"))
                assertEquals(gestureState, workspace.snapshot().state)
                assertEquals(guiSetting, workspace.listPhysics().single { it.id == group.id }.setting)
                vm.endEditorGesture(); workspace.awaitEditorDrafts()
                withTimeout(10000) { vm.state.first { !it.editorDraftBusy } }
                assertNull(vm.state.value.errorMessage)
                assertEquals(gestureNodes + 1, workspace.history().nodes.size)
                assertEquals("Final name", workspace.listPhysics().single { it.id == group.id }.setting.name)
                val duplicate = CompletableDeferred<String>()
                gui { vm.createPhysicsGroup(workspace.listPhysics().single { it.id == group.id }.setting) { duplicate.complete(it) } }
                val duplicateId = withTimeout(10000) { duplicate.await() }
                assertTrue(workspace.listPhysics().single { it.id == duplicateId }.setting.outputs.isEmpty())
                gui { vm.movePhysicsGroup(duplicateId, -1) }
                assertEquals(duplicateId, workspace.listPhysics().first().id)
                gui { vm.setProjectFps(30) }
                assertEquals(30, workspace.physicsFps())
                gui { vm.removePhysicsGroup(duplicateId) }
                val physicsFile = temporary.resolve("input.physics3.json")
                Files.writeString(physicsFile, Physics3Json.write(listOf(group.copy(id = "Imported", name = "Imported")), 60)!!)
                val imported = CompletableDeferred<String>()
                gui { vm.importPhysics(physicsFile.toString()) { imported.complete(it) } }
                assertEquals("Imported", withTimeout(10000) { imported.await() })
                assertEquals(60, workspace.physicsFps())
                val before = workspace.listPhysics(); val history = workspace.history().nodes
                val archive = temporary.resolve("physics-panel.psd2live")
                public("project_save_as", buildJsonObject { put("path", archive.toString()) })
                public("project_open", buildJsonObject { put("path", archive.toString()) })
                assertEquals(before, workspace.listPhysics()); assertEquals(history, workspace.history().nodes)
                val files = public("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                    .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                val exported = Physics3Json.read(Files.readString(files.single { it.toString().endsWith(".physics3.json") }))
                assertEquals(before.filter { it.active }.map { it.setting }, exported.settings)
                assertEquals(60f, exported.fps)
            }
        } }
    }
}
