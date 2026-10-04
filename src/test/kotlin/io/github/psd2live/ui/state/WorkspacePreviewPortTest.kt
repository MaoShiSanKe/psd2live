package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.application.WorkspacePose as ApplicationWorkspacePose
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class WorkspacePreviewPortTest {
    private class Host(private val vm: PSD2LiveViewModel) : WorkspaceBackendStub() {
        var poses: Map<String, ApplicationWorkspacePose> = emptyMap()
        var submissions = 0
        override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true, "Artwork", 64, 96,
            false, "Ready", null, emptyList(), emptyList(), state = "pose:$submissions")
        override fun commitAuthoredPoses(state: String, poses: Map<String, ApplicationWorkspacePose>) {
            assertEquals("pose:$submissions", state)
            this.poses += poses; submissions++
        }
        override suspend fun authorPose(arguments: JsonObject, author: MutationAuthor): JsonObject {
            assertEquals("pose:$submissions", arguments.getValue("state").jsonPrimitive.content)
            val current = vm.state.value
            val values = current.parameterValues + arguments.getValue("values").jsonObject.map { (id, value) -> ParameterId(id) to value.jsonPrimitive.float }.toMap()
            val pose = ApplicationWorkspacePose(values, current.lockedParameters)
            poses += current.activeWorkspace.id to pose; submissions++
            vm.applyPreviewSession(current, pose, true)
            return buildJsonObject { put("state", "pose:$submissions"); put("project_id", "project"); put("history_node_id", "head")
                putJsonObject("values") { values.forEach { (id, value) -> put(id.raw, value) } }; putJsonArray("locked") { pose.locked.forEach { add(it.raw) } }; putJsonArray("keyed") {} }
        }
        override fun controlPlayback(arguments: JsonObject) = buildJsonObject {
            put("state", "pose:$submissions"); put("project_id", "project"); put("workspace_id", vm.state.value.activeWorkspace.id)
            put("time", 0); put("elapsed", 0); put("playing", false); put("tracking", false); put("pointer_active", false); put("animation", false)
            putJsonObject("values") { vm.state.value.parameterValues.forEach { (id, value) -> put(id.raw, value) } }
        }
        override fun playbackFrame(dt: Float?) = controlPlayback(JsonObject(emptyMap()))
        override fun previewPhysics(arguments: JsonObject) = buildJsonObject {
            put("project_id", "project"); put("state", "pose:$submissions"); put("workspace_id", vm.state.value.activeWorkspace.id)
            put("settled", true); putJsonObject("outputs") {}; putJsonObject("values") {}
        }
        override suspend fun setPreviewSession(arguments: JsonObject): JsonObject {
            val current = vm.state.value
            assertEquals("pose:$submissions", arguments.getValue("state").jsonPrimitive.content)
            val parameters = current.previewModel!!.rig.puppet.parameters
            val pose = PreviewSessions.edit(parameters, PreviewSessions.normalize(parameters, ApplicationWorkspacePose(current.parameterValues, current.lockedParameters)),
                arguments["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float },
                arguments["locks"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.boolean },
                reset = arguments.getValue("mode").jsonPrimitive.content == "reset")
            poses += current.activeWorkspace.id to pose; submissions++
            vm.applyPreviewSession(current, pose, true)
            return JsonObject(PreviewSessions.encode(pose) + mapOf("state" to JsonPrimitive("pose:$submissions"), "project_id" to JsonPrimitive("project"), "history_node_id" to JsonPrimitive("head")))
        }
    }
    private suspend fun settled(vm: PSD2LiveViewModel) = withTimeout(5000) { vm.state.first { !it.canvasEditBusy } }
    private suspend fun fixture(action: suspend (PSD2LiveViewModel, Host) -> Unit) {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) }); val root = simulationFixture(runtime)
        val preview = root.model.copy(rig = root.model.rig.copy(puppet = root.model.rig.puppet.copy(
            parameters = listOf(Parameter(ParameterId("Axis"), "Axis", -1f, 1f, 0f)))))
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(projectId = "project", analysis = preview.analysis, previewModel = preview))
            val host = Host(vm); vm.attachWorkspace(host); action(vm, host)
        }
    }

    @Test fun sliderLockAndResetSendFrozenAuthoredPosesThroughTheNeutralPort() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis"); val workspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(axis, 0.6f)
            settled(vm)
            assertEquals(1, host.submissions); val first = host.poses
            assertEquals(0.6f, first.getValue(workspace).values.getValue(axis))
            vm.toggleParameterLock(axis)
            settled(vm)
            assertEquals(setOf(axis), host.poses.getValue(workspace).locked)
            vm.setParameterValues(mapOf(axis to 0.2f))
            settled(vm)
            assertEquals(0.2f, host.poses.getValue(workspace).values.getValue(axis))
            assertEquals(0.6f, first.getValue(workspace).values.getValue(axis))
            vm.resetAllParameters()
            settled(vm)
            assertEquals(0f, host.poses.getValue(workspace).values.getValue(axis)); assertTrue(host.poses.getValue(workspace).locked.isEmpty())
        }
    }

    @Test fun multipleWorkspacePoseNotificationsKeepTheInactiveAuthoredPose() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis"); val first = vm.state.value.activeWorkspace.id
            vm.setParameterValue(axis, 0.8f)
            settled(vm)
            vm.addWorkspace(); val second = vm.state.value.activeWorkspace.id
            vm.setParameterValue(axis, -0.8f)
            settled(vm)
            assertEquals(0.8f, host.poses.getValue(first).values.getValue(axis))
            assertEquals(-0.8f, host.poses.getValue(second).values.getValue(axis))
            assertNotEquals(first, second)
        }
    }

    @Test fun duplicatedWorkspacePersistsItsCopiedPoseBeforeAnyNewGesture() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis"); val first = vm.state.value.activeWorkspace.id
            vm.setParameterValue(axis, 0.8f); settled(vm); vm.toggleParameterLock(axis); settled(vm)
            val second = vm.duplicateWorkspace()
            assertEquals(ApplicationWorkspacePose(mapOf(axis to 0.8f), setOf(axis)), host.poses.getValue(second))
            vm.setStateForTest(vm.state.value.copy(workspaces = vm.state.value.workspaces.map { workspace ->
                if (workspace.id == first) workspace.withPose(workspace.pose!!.copy(parameterValues = mapOf(axis to -0.8f))) else workspace
            }))
            vm.setParameterValue(axis, 0.3f)
            settled(vm)
            assertEquals(0.8f, host.poses.getValue(first).values.getValue(axis))
            assertEquals(0.3f, host.poses.getValue(second).values.getValue(axis))
        }
    }
}
