package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspacePoseTimelineIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiAndPublicGesturesSharePoseKeysHistoryAndArchiveAndExportResults() = runBlocking<Unit> {
        val art = temporary.resolve("art.png")
        val image = BufferedImage(48, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 5..58) for (x in 8..39) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", art.toFile())
        val axis = ParameterId("Axis")
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 64); putJsonArray("layers") { add(buildJsonObject {
                        put("path", art.toString()); put("name", "Synthetic artwork"); put("role", "objects")
                    }) }
                })
                workspace.applyDocumentEdits(workspace.snapshot().state, "Timeline", listOf(
                    WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                        put("parameter_id", axis.raw); put("name", "Axis"); put("min", -1); put("max", 1)
                    }),
                    WorkspaceDocumentOperation("motion_put", buildJsonObject { put("clip", MotionClips.toJson(MotionClip("take", "Take"))) }),
                ), MutationAuthor.USER)
                val mesh = workspace.currentPuppet()!!.drawables.single()
                workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put(axis.raw, 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(mesh.mesh!!.positions.size) { JsonPrimitive(0.3f) })) }
                    putJsonObject("channels") { put("opacity", 0.4) }
                }) }, MutationAuthor.USER)
                WorkspaceOperations(workspace).use { operations ->
                    val actor = WorkspaceOperationContext(MutationAuthor.AGENT)
                    var sequence = 0
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val definition = operations.registry.definition(id)
                        val before = workspace.snapshot()
                        val input = if (definition.kind == WorkspaceOperationKind.QUERY) fields else JsonObject(fields + buildJsonObject {
                            put("project_id", before.projectId); put("state", before.state); put("request_id", "pose-${sequence++}")
                        })
                        val response = operations.registry.invoke(id, input, actor).data
                        if (!definition.jobBacked) return response
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", response.getValue("id")) }, actor).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), definition.jobResultSchema!!)
                        assertEquals(response.getValue("id"), operations.registry.invoke(id, input, actor).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    suspend fun gui(action: () -> Unit) {
                        val count = workspace.history().nodes.size
                        action(); withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                        assertNull(vm.state.value.errorMessage)
                        assertEquals(count + 1, workspace.history().nodes.size)
                        assertEquals("user", workspace.history().nodes.last().actor)
                    }
                    val origin = workspace.history().nodes.last().id
                    vm.openMotionInEditor("take"); vm.setMotionAutoKey(true); vm.setMotionPlayhead(1f)
                    val gestureState = workspace.snapshot().state; val gestureCount = workspace.history().nodes.size
                    vm.beginParameterScrub(); vm.setParameterValue(axis, 0.4f); vm.setParameterValue(axis, 0.8f)
                    assertEquals(gestureState, workspace.snapshot().state)
                    assertEquals(gestureCount, workspace.history().nodes.size)
                    assertEquals(0f, workspace.previewSession().getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
                    gui { vm.endParameterScrub() }
                    val guiClip = workspace.motionClips().single()
                    assertEquals(listOf(0f, 1f), guiClip.curve(axis.raw)!!.keys.map { it.time })
                    assertEquals(listOf(0f, 0.8f), guiClip.curve(axis.raw)!!.keys.map { it.value })
                    assertEquals(0.8f, workspace.previewSession().getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
                    workspace.checkoutHistory(origin, MutationAuthor.USER)
                    call("preview_reset", buildJsonObject {})
                    val result = call("preview_pose", buildJsonObject {
                        putJsonObject("values") { put(axis.raw, 0.8f) }
                        putJsonObject("auto_key") { put("clip_id", "take"); put("time", 1); put("snap", true) }
                    })
                    assertEquals(2, result.getValue("keyed").jsonArray.size)
                    assertEquals(guiClip, workspace.motionClips().single())
                    val keysOrigin = workspace.history().nodes.last().id
                    vm.motionEditor.selection = setOf(MotionKeyRef(axis.raw, 1f))
                    vm.beginMotionKeyDrag(); vm.dragMotionKeys(0.25f, -0.1f)
                    val dragState = workspace.snapshot().state
                    gui { vm.endMotionKeyDrag() }
                    val guiMoved = workspace.motionClips().single()
                    assertTrue(dragState != workspace.snapshot().state)
                    workspace.checkoutHistory(keysOrigin, MutationAuthor.USER)
                    call("motion_move_keys", buildJsonObject {
                        put("id", "take"); put("dt", 0.25f); put("dv", -0.1f)
                        putJsonArray("selection") { add(buildJsonObject { put("parameter", axis.raw); put("time", 1) }) }
                    })
                    assertEquals(guiMoved, workspace.motionClips().single())
                    val movedOrigin = workspace.history().nodes.last().id
                    vm.motionEditor.selection = setOf(MotionKeyRef(axis.raw, 1.25f))
                    gui { vm.updateSelectedMotionKeys { _, key -> key.copy(interpolation = MotionInterpolation.LINEAR, outHandle = MotionHandle(0.2f, 0.1f)) } }
                    val guiReplaced = workspace.motionClips().single()
                    workspace.checkoutHistory(movedOrigin, MutationAuthor.USER)
                    call("motion_replace_keys", buildJsonObject {
                        put("id", "take"); putJsonArray("keys") { add(buildJsonObject {
                            put("parameter", axis.raw); put("from_time", 1.25f); putJsonObject("key") {
                                put("time", 1.25f); put("value", 0.7f); put("interpolation", "LINEAR")
                                put("out", buildJsonArray { add(0.2f); add(0.1f) })
                            }
                        }) }
                    })
                    assertEquals(guiReplaced, workspace.motionClips().single())
                    call("rig_edit_structure", buildJsonObject { putJsonArray("edits") { add(buildJsonObject {
                        put("action", "static"); put("kind", "mesh"); put("id", mesh.id.raw); put("user_data", "Authored metadata")
                    }) } })
                    assertEquals("Authored metadata", workspace.currentPuppet()!!.drawables.single().userData)
                    val beforeState = workspace.snapshot().state
                    call("preview_playback", buildJsonObject { put("mode", "seek"); put("clip_id", "take"); put("time", 0.5f) })
                    val sampled = call("preview_playback_get", buildJsonObject {})
                    assertEquals(MotionClips.sampleAll(guiReplaced, 0.5, false).getValue(axis), sampled.getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
                    assertEquals(beforeState, workspace.snapshot().state)
                    assertEquals(0.8f, vm.state.value.parameterValues.getValue(axis))
                    call("settings_update", buildJsonObject { putJsonObject("changes") {
                        put("meshOnly", false); put("exportMoc3", true); put("exportMotions", true)
                    } })
                    val frame = WorkspaceModelViewRequest(parameters = mapOf(axis.raw to 0.8f),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 64f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val png = workspace.renderModel(frame).png; val before = workspace.currentPuppet()!!
                    val archive = temporary.resolve("pose-timeline.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    val nodes = workspace.history().nodes
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(nodes, workspace.history().nodes); assertEquals(guiReplaced, workspace.motionClips().single())
                    assertEquals(0.8f, workspace.previewSession().getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
                    assertEquals("Authored metadata", workspace.currentPuppet()!!.drawables.single().userData)
                    assertContentEquals(png, workspace.renderModel(frame).png)
                    val files = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                        .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    assertTrue(files.any { it.toString().endsWith(".motion3.json") && Files.readString(it).contains(axis.raw) })
                    val exported = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.toString().endsWith(".cmo3") })).puppet
                    val evaluator = CpuDeformationEvaluator()
                    for (value in listOf(0f, 0.5f, 1f)) {
                        val expected = evaluator.evaluate(before, mapOf(axis to value)); val actual = evaluator.evaluate(exported, mapOf(axis to value))
                        expected.worldPositions.forEach { (id, vertices) ->
                            vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                            assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                        }
                    }
                    val visuals = Path.of("build/pose-timeline-command-visual").toAbsolutePath(); Files.createDirectories(visuals)
                    Files.write(visuals.resolve("before-reopen.png"), png)
                    Files.write(visuals.resolve("after-reopen.png"), workspace.renderModel(frame).png)
                }
            }
        }
    }
}
