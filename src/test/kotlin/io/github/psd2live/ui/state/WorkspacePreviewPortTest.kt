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
        @Volatile var submissions = 0
        /** Each authored pose commit waits for the next gate, when one is queued. */
        val gates = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.CompletableDeferred<Unit>>()
        @Volatile var failNext: String? = null
        override fun authoredPose(workspaceId: String) = poses[workspaceId]
            ?: ApplicationWorkspacePose(vm.state.value.previewModel!!.rig.puppet.parameters.associate { it.id to it.default }, emptySet())
        // The revision is the editor document's own, so ending a slider session leaves no editor draft to submit.
        override fun snapshot() = WorkspaceProjectSnapshot("project", WorkspaceRevisions.of(WorkspaceStateCodec.document(vm.state.value)), "head", true, "Artwork", 64, 96,
            false, "Ready", null, emptyList(), emptyList(), state = "pose:$submissions")
        override fun commitAuthoredPoses(state: String, poses: Map<String, ApplicationWorkspacePose>) {
            assertEquals("pose:$submissions", state)
            this.poses += poses; submissions++
        }
        override suspend fun authorPose(arguments: JsonObject, author: MutationAuthor): JsonObject {
            gates.poll()?.await()
            failNext?.let { failNext = null; throw IllegalStateException(it) }
            assertEquals("pose:$submissions", arguments.getValue("state").jsonPrimitive.content)
            val current = vm.state.value
            // Like the desktop backend: a queued GUI change commits in its own workspace over the committed pose.
            val commit = kotlinx.coroutines.currentCoroutineContext()[PendingPoseCommit]
            val workspace = commit?.workspaceId ?: current.activeWorkspace.id
            val values = authoredPose(workspace).values + arguments.getValue("values").jsonObject.map { (id, value) -> ParameterId(id) to value.jsonPrimitive.float }.toMap()
            val pose = ApplicationWorkspacePose(values, current.lockedParameters)
            poses += workspace to pose; submissions++
            vm.applyPreviewSession(current, pose, true, commit)
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
            vm.applyPreviewSession(current, pose, true, kotlinx.coroutines.currentCoroutineContext()[PendingPoseCommit])
            return JsonObject(PreviewSessions.encode(pose) + mapOf("state" to JsonPrimitive("pose:$submissions"), "project_id" to JsonPrimitive("project"), "history_node_id" to JsonPrimitive("head")))
        }
    }
    private suspend fun settled(vm: PSD2LiveViewModel) = withTimeout(5000) { vm.state.first { !it.workspaceEditBusy } }
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

    @Test fun aChangeShowsEverywhereAtOnceAndAnEarlierCommitNeverPullsItBack() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis"); val workspace = vm.state.value.activeWorkspace.id; val status = vm.state.value.statusText
            val first = kotlinx.coroutines.CompletableDeferred<Unit>(); val second = kotlinx.coroutines.CompletableDeferred<Unit>()
            host.gates += first; host.gates += second
            vm.setParameterValue(axis, 0.6f)
            // The authored pose, the frame pose canvases and physics resolve at, and the busy gate move together.
            assertEquals(0.6f, vm.state.value.parameterValues[axis])
            assertEquals(0.6f, vm.parameterScrubPose(vm.state.value, mapOf(axis to 0f))[axis])
            assertTrue(vm.state.value.poseCommitBusy && vm.state.value.workspaceEditBusy)
            // A second change while the first still commits queues instead of being refused.
            vm.setParameterValue(axis, -0.4f)
            assertEquals(-0.4f, vm.state.value.parameterValues[axis])
            first.complete(Unit)
            withTimeout(5000) { while (host.submissions < 1) kotlinx.coroutines.delay(5) }
            assertEquals(0.6f, host.poses.getValue(workspace).values.getValue(axis))
            assertEquals(-0.4f, vm.state.value.parameterValues[axis], "the landed first commit must not pull the newer value back")
            assertEquals(-0.4f, vm.parameterScrubPose(vm.state.value, mapOf(axis to 0.6f))[axis])
            second.complete(Unit)
            settled(vm)
            assertEquals(2, host.submissions)
            assertEquals(-0.4f, host.poses.getValue(workspace).values.getValue(axis))
            assertEquals(-0.4f, vm.state.value.parameterValues[axis])
            assertEquals(status, vm.state.value.statusText, "no change was refused")
            assertEquals(0f, vm.parameterScrubPose(vm.state.value, mapOf(axis to 0f))[axis], "no pending change remains over frames")
        }
    }

    @Test fun aReleasedSliderKeepsItsValueWhileTheCommitIsPending() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis")
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); host.gates += gate
            vm.beginParameterScrub()
            vm.setParameterValue(axis, 0.5f)
            assertEquals(0.5f, vm.parameterScrubValueOf(axis))
            assertEquals(0.5f, vm.state.value.parameterValues[axis], "every view shows the sample while dragging")
            assertEquals(0, host.submissions, "samples are not committed")
            vm.endParameterScrub()
            assertNull(vm.parameterScrubValueOf(axis))
            assertEquals(0.5f, vm.state.value.parameterValues[axis], "the slider must not fall back to the old value before the commit lands")
            gate.complete(Unit)
            settled(vm)
            assertEquals(0.5f, vm.state.value.parameterValues[axis])
            assertEquals(1, host.submissions)
        }
    }

    @Test fun aFailedCommitReturnsEveryViewToTheCommittedPose() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis"); val workspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(axis, 0.3f); settled(vm)
            host.failNext = "Rejected by the test host"
            vm.setParameterValue(axis, 0.9f)
            settled(vm)
            assertEquals(0.3f, host.poses.getValue(workspace).values.getValue(axis))
            assertEquals(0.3f, vm.state.value.parameterValues[axis])
            assertEquals(0.3f, vm.parameterScrubPose(vm.state.value, mapOf(axis to 0.3f))[axis])
            assertEquals("Rejected by the test host", vm.state.value.statusText)
        }
    }

    @Test fun snappingToAKeyMovesTheAuthoredPoseItselfAndCommitsOnce() = runBlocking<Unit> {
        fixture { vm, host ->
            val axis = ParameterId("Axis")
            vm.setParameterValue(axis, 0.4f); settled(vm)
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            assertTrue(vm.snapAxesToNearestKeys(listOf(KeyformAxis(axis, floatArrayOf(-1f, 0f, 1f)))) { ready.complete(Unit) })
            // Panels read the authored pose, so the eased frames have to land there and not only in the preview frame.
            withTimeout(5000) { vm.state.first { val value = it.parameterValues[axis] ?: 0.4f; value < 0.39f && value > 0.01f } }
            withTimeout(5000) { ready.await() }
            settled(vm)
            assertEquals(0f, vm.state.value.parameterValues[axis])
            assertEquals(0f, host.poses.getValue(vm.state.value.activeWorkspace.id).values.getValue(axis))
            assertEquals(2, host.submissions)
        }
    }

    @Test fun scrubbingFollowsSkeletonConstraintsBeforeTheRelease() = runBlocking<Unit> {
        fixture { vm, host ->
            val spec = SkeletonSpec().withCustomBone(0f, 0f, 0f, 50f).withCustomBone(0f, 50f, 0f, 100f, "custom_1")
                .let { it.withIkTarget(it.bones.last().id, SkeletonIkTarget(40f, 70f)) }
            val preview = vm.state.value.previewModel!!
            vm.setStateForTest(vm.state.value.copy(previewModel = preview.copy(config = preview.config.copy(
                rigEdits = preview.config.rigEdits.copy(skeleton = spec)))))
            val parent = ParameterId(spec.bones.first().parameterId); val child = ParameterId(spec.bones.last().parameterId)
            vm.beginParameterScrub()
            vm.setParameterValue(parent, 30f)
            assertNotNull(vm.parameterScrubValueOf(child), "the constrained bone follows while dragging, not only after the commit")
            assertEquals(vm.parameterScrubValueOf(child), vm.parameterScrubPose(vm.state.value, emptyMap())[child])
        }
    }
}
