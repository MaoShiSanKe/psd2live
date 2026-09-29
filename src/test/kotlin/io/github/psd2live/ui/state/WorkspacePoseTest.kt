package io.github.psd2live.ui.state

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import io.github.psd2live.project.WorkspaceStateCodec
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Parameter
import io.github.psd2live.core.*
import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.test.*

class WorkspacePoseTest {
    private val parameter = ParameterId("pose")

    private fun preview(): RigPreviewModel {
        val layer = WorkspaceSourceLayer(LayerId("body"), "body", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { -1 }), null, null, true)
        val preview = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            PipelineConfig(meshOnly = true, atlasSize = 256))
        return preview.copy(rig = preview.rig.copy(puppet = preview.rig.puppet.copy(
            parameters = listOf(Parameter(parameter, "Pose", -1f, 1f, 0f)))))
    }

    @Test fun scrubbingWithoutPreviewCanvasUpdatesEditPoseAndKeyingUsesTheLatestEdit() {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f), MotionKey(1f, 1f)))))
            vm.setStateForTest(vm.state.value.copy(previewModel = preview,
                rigEdits = vm.state.value.rigEdits.copy(motionClips = listOf(clip))))
            val first = vm.state.value.activeCanvas.id
            val second = vm.addCanvas(CanvasMode.EDIT, focus = false)
            vm.openMotionInEditor(clip.id)
            vm.setMotionPlayhead(0.75f)
            assertEquals(0.5f, vm.canvasEditorFor(first).state.parameterValues[parameter])
            assertEquals(0.5f, vm.canvasEditorFor(second).state.parameterValues[parameter])
            assertEquals(2, vm.state.value.activeWorkspace.canvases.size)
            assertTrue(vm.state.value.activeWorkspace.canvases.all { it.mode == CanvasMode.EDIT })
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, first) {
                it.copy(previewParameterValues = mapOf(parameter to -0.8f))
            }
            vm.setParameterValue(parameter, 0.25f)
            vm.keyCurrentPose()
            assertEquals(0.25f, vm.editingMotionClip()!!.curve(parameter.raw)!!.keys.single { it.time == 0.75f }.value)
        }
    }

    @Test fun modelChangesNormalizeEveryWorkspaceAndCleanMotionTracks() {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = preview,
                rigEdits = vm.state.value.rigEdits.copy(motionClips = listOf(MotionClip("clip", "Clip",
                    curves = listOf(MotionCurve(parameter.raw, listOf(MotionKey(0f, 0.9f)))))))))
            vm.setParameterValue(parameter, 0.9f)
            val first = vm.state.value.activeWorkspace.id
            vm.addWorkspace()
            vm.setParameterValue(parameter, -0.9f)
            val added = ParameterId("new")
            vm.updatePuppetModel { it.copy(parameters = listOf(
                Parameter(parameter, "Pose", -0.5f, 0.5f, 0f), Parameter(added, "New", 0f, 1f, 0.3f))) }
            assertEquals(-0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.3f, vm.state.value.parameterValues[added])
            vm.setActiveWorkspace(first)
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.3f, vm.state.value.parameterValues[added])
            assertEquals(0.5f, vm.motionClips.single().curves.single().keys.single().value)
            vm.updatePuppetModel { it.copy(parameters = it.parameters.filterNot { it.id == parameter }) }
            assertTrue(vm.motionClips.single().curves.isEmpty())
            assertTrue(vm.state.value.workspaces.all { parameter !in it.pose!!.parameterValues })
        }
    }

    @Test fun nonFocusedAndHiddenCanvasWritesReachEveryModeWithoutChangingSelection() {
        PSD2LiveViewModel().use { vm ->
            val first = vm.state.value.activeCanvas.id
            vm.selectLayer("first")
            val second = vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            vm.setModuleVisible(second, false)
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, second, CanvasMode.PREVIEW) {
                it.copy(parameterValues = mapOf(parameter to 0.75f), selectedLayerId = "second")
            }
            assertEquals(first, vm.state.value.activeCanvas.id)
            assertEquals("first", vm.state.value.selectedLayerId)
            assertTrue(second in vm.state.value.activeWorkspace.hiddenModules)
            for (id in listOf(first, second)) for (mode in CanvasMode.entries)
                assertEquals(0.75f, vm.state.value.forCanvas(id, mode = mode).parameterValues[parameter])
            assertEquals("second", vm.state.value.forCanvas(second).selectedLayerId)
        }
    }

    @Test fun workspaceSwitchAndPersistenceKeepIndependentWorkspacePoses() {
        PSD2LiveViewModel().use { vm ->
            val firstWorkspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(parameter, 0.25f)
            vm.addWorkspace()
            val secondWorkspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(parameter, -0.75f)
            vm.setActiveWorkspace(firstWorkspace)
            assertEquals(0.25f, vm.state.value.parameterValues[parameter])
            vm.setActiveWorkspace(secondWorkspace)
            assertEquals(-0.75f, vm.state.value.parameterValues[parameter])
            val restored = WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(vm.state.value))
            assertEquals(-0.75f, restored.parameterValues[parameter])
            assertEquals(0.25f, restored.forCanvas(restored.workspaces.first { it.id == firstWorkspace }.activeCanvasId,
                firstWorkspace).parameterValues[parameter])
        }
    }

    @Test fun authoringDropsStalePreviewValuesWithoutAddingLocks() {
        PSD2LiveViewModel().use { vm ->
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, vm.state.value.activeCanvas.id) {
                it.copy(previewParameterValues = mapOf(parameter to -1f), animationEnabled = true)
            }
            vm.setParameterValue(parameter, 0.5f)
            assertFalse(vm.state.value.animationEnabled)
            assertTrue(vm.state.value.previewParameterValues.isEmpty())
            assertTrue(vm.state.value.activeWorkspace.pose!!.authoringPose)
            vm.resetParameter(parameter)
            assertTrue(vm.state.value.lockedParameters.isEmpty())
        }
    }

    @Test fun animationAndToolsNeverRevealOrCreateMissingCanvases() {
        PSD2LiveViewModel().use { vm ->
            vm.addWorkspace(WorkspacePreset.BLANK)
            val before = vm.state.value.activeWorkspace
            vm.ensureEditCanvas()
            vm.setAnimationEnabled(true)
            vm.setMouseTrackingEnabled(true)
            vm.triggerMotion("Blink")
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw, listOf(MotionKey(0f, 1f)))))
            vm.setStateForTest(vm.state.value.copy(rigEdits = vm.state.value.rigEdits.copy(motionClips = listOf(clip))))
            vm.openMotionInEditor(clip.id)
            vm.setMotionPlayhead(0f)
            val after = vm.state.value.activeWorkspace
            assertEquals(before.canvases.map { it.id to it.mode }, after.canvases.map { it.id to it.mode })
            assertEquals(before.hiddenModules, after.hiddenModules)
            assertEquals(before.activeCanvasId, after.activeCanvasId)
        }
    }
}
