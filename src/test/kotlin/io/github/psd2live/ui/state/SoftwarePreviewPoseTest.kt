package io.github.psd2live.ui.state

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.core.*
import io.github.psd2live.application.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.umamo.format.art.*
import org.umamo.runtime.model.Parameter
import kotlin.test.*

class SoftwarePreviewPoseTest {
    @Test fun pausedPhysicsPublishesOnlyTheComposedPoseDuringScrubbing() = runBlocking(Dispatchers.Main) {
        val layer = WorkspaceSourceLayer(LayerId("body"), "body", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { -1 }), null, null, true)
        val base = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            PipelineConfig(meshOnly = true, atlasSize = 256))
        val head = StandardParameters.ANGLE_X
        val hair = StandardParameters.HAIR_FRONT
        val edits = RigEditOverlay(physicsEdits = listOf(RigPhysicsEdit("Ribbon", "Ribbon",
            listOf(PhysicsInput(head.raw, 100f, PhysicsSourceType.X)),
            listOf(PhysicsOutput(hair.raw, 1, 1f)), listOf(PhysicsSegment(8f, 0.9f, 0.9f, 1.2f)))))
        val model = base.copy(config = base.config.copy(meshOnly = false, generatePhysics = true, rigEdits = edits),
            rig = base.rig.copy(puppet = base.rig.puppet.copy(parameters = listOf(
            Parameter(head, "Head", -30f, 30f, 0f), Parameter(hair, "Hair", -1f, 1f, 0f)))))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
        runtime.install(runtime.state.value.state, "project", WorkspaceDocument(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            emptyMap(), emptySet(), emptyMap(), emptyMap(), edits), model)
        val playback = WorkspacePlaybackSessions(runtime)
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(projectId = "project", previewModel = model, sdkStatus = "unavailable",
                parameterValues = mapOf(head to 0f, hair to 0f), generatePhysics = true, meshOnly = false,
                rigEdits = edits))
            vm.attachWorkspace(object : WorkspaceBackendStub() {
                override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
                override fun previewPhysics(arguments: JsonObject) = playback.physics("project", arguments.getValue("state").jsonPrimitive.content,
                    vm.state.value.activeWorkspace.id, arguments)
                override fun controlPlayback(arguments: JsonObject) = playback.configure("project", arguments.getValue("state").jsonPrimitive.content,
                    vm.state.value.activeWorkspace.id, arguments)
                override fun playbackFrame(dt: Float?) = playback.frame(vm.state.value.activeWorkspace.id, dt)
            })
            vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
            val key = vm.canvasRenderKey(vm.state.value.activeCanvas.id)
            vm.beginParameterScrub()
            try {
                repeat(12) { index ->
                    vm.setParameterValue(head, 10f + index)
                    Thread.sleep(17)
                    val poses = mutableListOf<Map<org.umamo.runtime.model.ParameterId, Float>>()
                    val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        vm.state.collect { poses += it.previewParameterValues }
                    }
                    poses.clear()
                    vm.requestSdkFrame(8, 8, 1f, 0f, 0f, viewId = key)
                    observer.cancelAndJoin()
                    assertEquals(1, poses.size, "frame $index publishes $poses")
                    assertEquals(10f + index, poses.single()[head])
                }
                assertTrue(kotlin.math.abs(vm.state.value.previewParameterValues[hair] ?: 0f) > 1e-3f)
            } finally {
                vm.cancelParameterScrub()
            }
        }
    }
}
