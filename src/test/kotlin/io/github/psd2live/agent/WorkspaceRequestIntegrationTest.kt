package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ProjectController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceRequestIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun actualCommandsReturnOpaqueCommitTokensAndRetryOnceAcrossReopening() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y, 0xff527090.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                WorkspaceOperations(workspace).use { operations ->
                    val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                    val initial = workspace.snapshot()
                    val createJob = operations.registry.invoke("project_create_artwork", buildJsonObject {
                        put("project_id", JsonNull); put("state", initial.state); put("request_id", "create")
                        put("width", 32); put("height", 32)
                        putJsonArray("layers") { add(buildJsonObject {
                            put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                        }) }
                    }, agent).data
                    val createdJob = operations.registry.invoke("job_wait", buildJsonObject { put("id", createJob.getValue("id")) }, agent).data
                    assertEquals("completed", createdJob.getValue("status").jsonPrimitive.content)
                    val created = createdJob.getValue("result").jsonObject
                    val before = workspace.snapshot()
                    assertEquals(before.state, created.getValue("state").jsonPrimitive.content)
                    assertEquals(before.projectId, created.getValue("project_id").jsonPrimitive.content)
                    assertNotEquals(before.historyHeadNodeId, before.state)
                    fun setting(id: String, state: String, strength: Int) = buildJsonObject {
                        put("request_id", id); put("project_id", before.projectId); put("state", state)
                        put("changes", buildJsonObject { put("headStrength", strength) })
                    }
                    assertFailsWith<WorkspaceConflict> {
                        operations.registry.invoke("settings_update", setting("history", before.historyHeadNodeId!!, 2), agent)
                    }
                    assertEquals(1, workspace.history().nodes.size)
                    val request = setting("edit", before.state, 2)
                    val edited = operations.registry.invoke("settings_update", request, agent)
                    suspend fun result(job: JsonObject): JsonObject {
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                        return terminal.getValue("result").jsonObject
                    }
                    val editedResult = result(edited.data)
                    val after = workspace.snapshot()
                    assertEquals(after.state, editedResult.getValue("state").jsonPrimitive.content)
                    assertEquals(after.historyHeadNodeId, editedResult.getValue("history_node_id").jsonPrimitive.content)
                    assertEquals(2, workspace.history().nodes.size)
                    assertEquals(edited, operations.registry.invoke("settings_update", request, agent))
                    assertEquals(2, workspace.history().nodes.size)
                    val noOp = result(operations.registry.invoke("settings_update", setting("no-op", after.state, 2), agent).data)
                    assertFalse(noOp.getValue("applied").jsonPrimitive.boolean)
                    assertEquals(after.state, noOp.getValue("state").jsonPrimitive.content)
                    result(operations.registry.invoke("settings_update", setting("user", after.state, 3), WorkspaceOperationContext(MutationAuthor.USER)).data)
                    assertEquals("user", workspace.history().nodes.last().actor)
                    val saved = workspace.snapshot()
                    val controller = ProjectController(vm)
                    val file = temp.resolve("saved.psd2live")
                    controller.save(workspace, file)
                    controller.open(workspace, file)
                    val reopened = workspace.snapshot()
                    assertEquals(saved.historyHeadNodeId, reopened.historyHeadNodeId)
                    assertNotEquals(saved.state, reopened.state)
                    assertFailsWith<WorkspaceConflict> {
                        operations.registry.invoke("settings_update", setting("stale-load", saved.state, 4), agent)
                    }
                    assertEquals(edited, operations.registry.invoke("settings_update", request, agent))
                    assertEquals(3, workspace.history().nodes.size)
                }
            }
        }
    }

    @Test fun lifecycleJobsSaveOpenAndExplicitlyDiscardWithoutDialogs() = runBlocking {
        val png = temp.resolve("source.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y, 0xff527090.toInt())
        ImageIO.write(image, "png", png.toFile())
        val archive = temp.resolve("portable.psd2live")
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256))
            DesktopWorkspace(vm, temp.resolve("lifecycle-store")).use { workspace ->
                vm.attachWorkspace(workspace)
                WorkspaceOperations(workspace).use { operations ->
                    val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                    fun request(id: String, fields: JsonObject = buildJsonObject {}) = buildJsonObject {
                        val snapshot = workspace.snapshot()
                        put("request_id", id); put("state", snapshot.state)
                        put("project_id", snapshot.projectId?.let(::JsonPrimitive) ?: JsonNull)
                        fields.forEach { (key, value) -> put(key, value) }
                    }
                    suspend fun invoke(id: String, input: JsonObject) = operations.registry.invoke(id, input, agent).data
                    suspend fun await(job: JsonObject): JsonObject {
                        val result = invoke("job_wait", buildJsonObject { put("id", job.getValue("id")); put("timeout_ms", 30000) })
                        assertTrue(result.getValue("terminal").jsonPrimitive.boolean, result.toString())
                        return result
                    }
                    val createJob = invoke("project_create_artwork", request("create", buildJsonObject {
                        put("width", 32); put("height", 32)
                        putJsonArray("layers") { add(buildJsonObject {
                            put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                        }) }
                    }))
                    assertEquals("completed", await(createJob).getValue("status").jsonPrimitive.content)
                    val created = workspace.snapshot()
                    val saveInput = request("save-as", buildJsonObject { put("path", archive.toString()) })
                    val saveJob = invoke("project_save_as", saveInput)
                    assertEquals(saveJob, invoke("project_save_as", saveInput))
                    val saved = await(saveJob)
                    assertEquals("completed", saved.getValue("status").jsonPrimitive.content)
                    assertEquals(created.state, saved.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                    assertEquals(archive.toString(), vm.state.value.projectFile)
                    assertFalse(vm.state.value.projectDirty)
                    assertEquals(1, workspace.history().nodes.size)
                    assertEquals("completed", await(invoke("settings_update", request("change", buildJsonObject {
                        putJsonObject("changes") { put("headStrength", 2) }
                    }))).getValue("status").jsonPrimitive.content)
                    val dirty = workspace.snapshot()
                    val rejected = await(invoke("project_open", request("reject", buildJsonObject { put("path", archive.toString()) })))
                    assertEquals("failed", rejected.getValue("status").jsonPrimitive.content)
                    assertEquals("unsaved_changes", rejected.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                    assertTrue(rejected.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("unsaved"))
                    assertEquals(dirty.state, workspace.snapshot().state)
                    assertTrue(vm.state.value.projectDirty)
                    assertFalse(vm.state.value.showProjectLocationDialog)
                    val opened = await(invoke("project_open", request("discard", buildJsonObject {
                        put("path", archive.toString()); put("discard_unsaved", true)
                    })))
                    assertEquals("completed", opened.getValue("status").jsonPrimitive.content)
                    val reopened = workspace.snapshot()
                    assertEquals(created.historyHeadNodeId, reopened.historyHeadNodeId)
                    assertNotEquals(created.state, reopened.state)
                    assertEquals(reopened.state, opened.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                    assertFalse(reopened.projectDirty)
                    val normalSave = await(invoke("project_save", request("save")))
                    assertEquals("completed", normalSave.getValue("status").jsonPrimitive.content)
                    assertEquals(1, workspace.history().nodes.size)
                    assertFalse(normalSave.getValue("result").jsonObject.getValue("applied").jsonPrimitive.boolean)
                    assertFailsWith<WorkspaceConflict> {
                        invoke("project_open", JsonObject(saveInput + mapOf("request_id" to JsonPrimitive("old"))))
                    }
                }
            }
        }
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("fresh-store")).use { workspace ->
                vm.attachWorkspace(workspace)
                WorkspaceOperations(workspace).use { operations ->
                    val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                    val pending = operations.registry.invoke("project_open", buildJsonObject {
                        put("request_id", "initial-open"); put("state", workspace.snapshot().state)
                        put("project_id", JsonNull); put("path", archive.toString())
                    }, agent).data
                    val opened = operations.registry.invoke("job_wait", buildJsonObject {
                        put("id", pending.getValue("id")); put("timeout_ms", 30000)
                    }, agent).data
                    assertEquals("completed", opened.getValue("status").jsonPrimitive.content, opened.toString())
                    assertEquals(workspace.snapshot().state, opened.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                }
            }
        }
    }
}
