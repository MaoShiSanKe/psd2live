package io.github.psd2live.ui.state

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.WorkspaceStateCodec
import org.umamo.format.art.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class ParameterSnapshotPreviewTest {
    private val axis = ParameterId("pose")
    private val snapshot = ParameterSnapshot("saved", 1, "", mapOf(axis to 0.8f))

    private fun model() = PSD2LivePipeline().buildPreview(
        WorkspaceSourceArt(8, 8, listOf(WorkspaceSourceLayer(
            LayerId("body"), "body", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { -1 }), null, null, true,
        )), emptyList()), PipelineConfig(meshOnly = true, atlasSize = 256),
    ).let { it.copy(rig = it.rig.copy(puppet = it.rig.puppet.copy(
        parameters = listOf(Parameter(axis, "Pose", -1f, 1f, 0f)),
    ))) }

    private fun viewModel() = PSD2LiveViewModel().also { vm ->
        vm.setStateForTest(vm.state.value.copy(previewModel = model(), parameterSnapshots = listOf(snapshot),
            parameterValues = mapOf(axis to -0.4f), lockedParameters = setOf(axis)))
    }

    @Test fun hoverAndExitLeaveThePoseLocksAndSavedProjectUntouched() {
        viewModel().use { vm ->
            val before = vm.state.value
            val saved = WorkspaceStateCodec.encode(before)
            val token = assertNotNull(vm.previewParameterSnapshot(snapshot.id))
            assertEquals(snapshot, vm.parameterSnapshotPreviewFor(before.activeCanvas.id))
            assertSame(before, vm.state.value)
            assertEquals(saved, WorkspaceStateCodec.encode(vm.state.value))
            vm.clearParameterSnapshotPreview(token)
            assertNull(vm.parameterSnapshotPreviewFor(before.activeCanvas.id))
            assertSame(before, vm.state.value)
        }
    }

    @Test fun anOldExitCannotClearANewerHoverEvenOfTheSameSnapshot() {
        viewModel().use { vm ->
            val old = assertNotNull(vm.previewParameterSnapshot(snapshot.id))
            val latest = assertNotNull(vm.previewParameterSnapshot(snapshot.id))
            vm.clearParameterSnapshotPreview(old)
            assertEquals(snapshot, vm.parameterSnapshotPreviewFor(vm.state.value.activeCanvas.id))
            vm.clearParameterSnapshotPreview(latest)
            assertNull(vm.parameterSnapshotPreviewFor(vm.state.value.activeCanvas.id))
        }
    }

    @Test fun hoverIsLocalToTheFocusedCanvasAndDoesNotReturnAfterSwitchingBack() {
        viewModel().use { vm ->
            val first = vm.state.value.activeCanvas.id
            val second = vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            vm.previewParameterSnapshot(snapshot.id)
            assertNull(vm.parameterSnapshotPreviewFor(second))
            vm.focusCanvas(second)
            assertNull(vm.parameterSnapshotPreviewFor(first))
            vm.focusCanvas(first)
            assertNull(vm.parameterSnapshotPreviewFor(first))
        }
    }

    @Test fun deletingOrReplacingTheProjectClearsTheHover() {
        viewModel().use { vm ->
            val initial = vm.state.value
            vm.previewParameterSnapshot(snapshot.id)
            vm.deleteParameterSnapshot(snapshot.id)
            assertNull(vm.parameterSnapshotPreviewFor(initial.activeCanvas.id))
            vm.setStateForTest(initial)
            assertNull(vm.parameterSnapshotPreviewFor(initial.activeCanvas.id))
            vm.previewParameterSnapshot(snapshot.id)
            vm.setStateForTest(initial.copy(projectOpenGeneration = initial.projectOpenGeneration + 1))
            assertNull(vm.parameterSnapshotPreviewFor(initial.activeCanvas.id))
        }
    }

    @Test fun workspaceChangesClearTheHover() {
        viewModel().use { vm ->
            val initial = vm.state.value
            vm.previewParameterSnapshot(snapshot.id)
            vm.addWorkspace()
            assertNull(vm.parameterSnapshotPreviewFor(vm.state.value.activeCanvas.id))
            vm.setActiveWorkspace(initial.activeWorkspace.id)
            assertNull(vm.parameterSnapshotPreviewFor(initial.activeCanvas.id))
        }
    }

    @Test fun previewUsesSavedValuesAndDefaultsWithinCurrentParameterRanges() {
        val added = ParameterId("new")
        val invalid = ParameterId("invalid")
        val pose = snapshot.copy(values = snapshot.values + mapOf(invalid to Float.NaN, ParameterId("removed") to 1f))
        assertEquals(mapOf(axis to 0.5f, added to 0.3f, invalid to 0.2f), pose.previewValues(listOf(
            Parameter(axis, "Pose", -0.5f, 0.5f, 0f),
            Parameter(added, "New", 0f, 1f, 0.3f),
            Parameter(invalid, "Invalid", 0f, 1f, 0.2f),
        )))
    }
}
