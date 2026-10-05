package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.delay
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

class WorkspaceSwingPreviewIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiAndAgentAuditionTheSameDraftWithoutSavingItsRigOrClockAndCommitEquivalentForms() = runBlocking<Unit> {
        val art = temporary.resolve("art.png"); val image = BufferedImage(48, 80, BufferedImage.TYPE_INT_ARGB)
        for (y in 4..75) for (x in 18..29) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject { put("width", 48); put("height", 80); putJsonArray("layers") { add(buildJsonObject {
                    put("path", art.toString()); put("name", "Synthetic strand"); put("role", "objects")
                }) } })
                val committed = workspace.currentPuppet()!!; val vmBaseline = vm.state.value.previewModel
                val target = committed.drawables.single().id.raw; val state = workspace.snapshot().state
                val pose = workspace.previewSession(); val origin = workspace.history().nodes.last().id
                fun assertCommittedBaseline() {
                    val actual = workspace.currentPuppet()!!
                    assertEquals(committed.drawables.map { it.id }, actual.drawables.map { it.id })
                    assertEquals(committed.deformers.map { it.id }, actual.deformers.map { it.id })
                    val evaluator = CpuDeformationEvaluator()
                    val expected = evaluator.evaluate(committed, emptyMap()); val result = evaluator.evaluate(actual, emptyMap())
                    expected.worldPositions.forEach { (id, vertices) -> assertContentEquals(vertices, result.worldPositions.getValue(id)) }
                }
                suspend fun settled(predicate: () -> Boolean) = withTimeout(10000) { while (!predicate()) delay(10) }
                vm.beginSwing(listOf(target)); settled { vm.swingSession != null }
                val guiSession = vm.swingSession!!
                vm.setSwingKinds(listOf(SwingKind.LATERAL, SwingKind.VERTICAL)); settled { vm.swingSession?.draft?.motions?.size == 2 }
                vm.setSwingSegments(0, 2); settled { vm.swingSession?.draft?.motions?.first()?.segments == 2 }
                vm.setSwingPreset(SwingPreset.CLOTH); settled { vm.swingSession?.draft?.preset == SwingPreset.CLOTH }
                vm.playSwing(true); settled { vm.swingSession?.playing == true }
                assertEquals(state, workspace.snapshot().state); assertEquals(committed, workspace.currentPuppet())
                assertSame(vmBaseline, vm.state.value.previewModel); assertEquals(pose, workspace.previewSession())
                assertTrue(vm.canvasEditor.preview !== committed)
                val guiDraft = workspace.swingPreviewFrame(guiSession.sessionId, 0.4f)
                val guiPuppet = guiDraft.model.rig.puppet
                WorkspaceOperations(workspace).use { operations ->
                    val actor = WorkspaceOperationContext(MutationAuthor.AGENT); var sequence = 0
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val definition = operations.registry.definition(id); val before = workspace.snapshot()
                        val input = if (definition.kind == WorkspaceOperationKind.QUERY) fields else JsonObject(fields + buildJsonObject {
                            put("project_id", before.projectId); put("state", before.state); put("request_id", "swing-${sequence++}")
                        })
                        val output = operations.registry.invoke(id, input, actor).data
                        if (!definition.jobBacked) return output
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", output.getValue("id")) }, actor).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), definition.jobResultSchema!!)
                        assertEquals(output.getValue("id"), operations.registry.invoke(id, input, actor).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    // Saving an active audition serializes the committed model and authored pose only.
                    val draftArchive = temporary.resolve("active-audition.psd2live")
                    call("project_save_as", buildJsonObject { put("path", draftArchive.toString()) })
                    call("project_open", buildJsonObject { put("path", draftArchive.toString()) })
                    assertCommittedBaseline(); assertTrue(workspace.listSwings().isEmpty())
                    assertEquals(pose.getValue("values"), workspace.previewSession().getValue("values"))
                    val begun = call("swing_preview", buildJsonObject { put("mode", "begin"); putJsonArray("targets") { add(target) } })
                    val agentId = begun.getValue("session_id")
                    call("swing_preview", buildJsonObject { put("mode", "update"); put("session_id", agentId); put("draft", guiDraft.draft.toJson()) })
                    call("swing_preview", buildJsonObject { put("mode", "play"); put("session_id", agentId); put("enabled", true) })
                    val frame = call("swing_preview_get", buildJsonObject { put("session_id", agentId); put("time", 0.4f) })
                    assertEquals(guiDraft.values.mapKeys { it.key.raw }, frame.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.float })
                    val view = call("swing_preview_render", buildJsonObject {
                        put("session_id", agentId); putJsonArray("bounds") { add(0); add(0); add(48); add(80) }; put("size", 256)
                        putJsonObject("parameters") { guiDraft.values.forEach { (id, value) -> put(id.raw, value) } }
                    })
                    assertTrue(view.isNotEmpty()); assertCommittedBaseline()
                    val baseNode = workspace.history().nodes.last().id
                    // A GUI commit and an agent commit start from the identical document and draft.
                    val beforeCount = workspace.history().nodes.size
                    vm.commitSwing(); settled { vm.swingSession == null && !vm.state.value.canvasEditBusy }
                    assertEquals(beforeCount + 1, workspace.history().nodes.size)
                    val guiCommit = workspace.currentPuppet()!!; val authored = workspace.listSwings().single()
                    workspace.checkoutHistory(baseNode, MutationAuthor.USER)
                    val next = call("swing_preview", buildJsonObject { put("mode", "begin"); putJsonArray("targets") { add(target) } })
                    call("swing_preview", buildJsonObject { put("mode", "update"); put("session_id", next.getValue("session_id")); put("draft", guiDraft.draft.toJson()) })
                    call("swing_preview_commit", buildJsonObject { put("session_id", next.getValue("session_id")) })
                    assertEquals(authored, workspace.listSwings().single())
                    val evaluator = CpuDeformationEvaluator()
                    for (value in listOf(-1f, 0f, 1f)) {
                        val parameters = authored.parameterIds.associate { ParameterId(it) to value }
                        val expected = evaluator.evaluate(guiCommit, parameters); val actual = evaluator.evaluate(workspace.currentPuppet()!!, parameters)
                        expected.worldPositions.forEach { (id, vertices) -> vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) } }
                    }
                    val archive = temporary.resolve("committed-audition.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(authored, workspace.listSwings().single())
                    val reopened = evaluator.evaluate(workspace.currentPuppet()!!, authored.parameterIds.associate { ParameterId(it) to 1f })
                    val draftPose = evaluator.evaluate(guiPuppet, authored.parameterIds.associate { ParameterId(it) to 1f })
                    draftPose.worldPositions.forEach { (id, vertices) -> vertices.indices.forEach { assertEquals(vertices[it], reopened.worldPositions.getValue(id)[it], 0.001f) } }
                    assertTrue(origin.isNotBlank()); assertTrue(Files.size(archive) > 0)
                }
            }
        }
    }
}
