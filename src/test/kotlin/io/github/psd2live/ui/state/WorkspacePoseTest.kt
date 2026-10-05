package io.github.psd2live.ui.state

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import org.umamo.runtime.model.ParameterId
import io.github.psd2live.core.*
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspacePoseTest {
    private val parameter = ParameterId("pose")
    @TempDir lateinit var temporary: Path

    private suspend fun settled(vm: PSD2LiveViewModel) {
        withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
        assertNull(vm.state.value.errorMessage)
    }
    private suspend fun putClip(workspace: DesktopWorkspace, clip: MotionClip) {
        workspace.applyDocumentEdits(workspace.snapshot().state, "Timeline fixture", listOf(WorkspaceDocumentOperation("motion_put",
            buildJsonObject { put("clip", MotionClips.toJson(clip)) })), MutationAuthor.USER)
    }
    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace) -> Unit) {
        val path = temporary.resolve("art.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..7) for (x in 0..7) image.setRGB(x, y, 0xff778899.toInt())
        ImageIO.write(image, "png", path.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 8); put("height", 8); putJsonArray("layers") { add(buildJsonObject {
                        put("path", path.toString()); put("name", "Synthetic artwork"); put("role", "objects")
                    }) }
                })
                workspace.applyDocumentEdits(workspace.snapshot().state, "Pose parameters", listOf(
                    WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                        put("parameter_id", parameter.raw); put("name", "Pose"); put("min", -1); put("max", 1)
                    }), WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                        put("parameter_id", "untracked"); put("name", "Untracked"); put("min", -10); put("max", 10); put("default", 2)
                    })), MutationAuthor.USER)
                action(vm, workspace)
            }
        }
    }

    @Test fun enteringPreviewResumesEffectsFromTheAuthoredPose() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val canvas = vm.state.value.activeCanvas.id
            vm.setParameterValue(parameter, 0.5f)
            settled(vm)
            vm.toggleParameterLock(parameter)
            settled(vm)
            assertTrue(vm.state.value.activeWorkspace.pose!!.authoringPose)
            vm.setCanvasMode(canvas, CanvasMode.PREVIEW)
            assertFalse(vm.state.value.activeWorkspace.pose!!.authoringPose)
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertTrue(parameter in vm.state.value.lockedParameters)
            assertTrue(vm.state.value.mouseTrackingEnabled)
            assertFalse(vm.state.value.animationEnabled)
        }
    }

    @Test fun focusingOrRevealingPreviewResumesEffectsAfterEditingInAnotherCanvas() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val edit = vm.state.value.activeCanvas.id
            val preview = vm.addCanvas(CanvasMode.PREVIEW)
            vm.focusCanvas(edit)
            vm.setParameterValue(parameter, 0.25f)
            settled(vm)
            assertTrue(vm.state.value.activeWorkspace.pose!!.authoringPose)
            vm.focusCanvas(preview)
            assertFalse(vm.state.value.activeWorkspace.pose!!.authoringPose)
            vm.focusCanvas(edit)
            vm.setModuleVisible(preview, false)
            vm.setParameterValue(parameter, 0.75f)
            settled(vm)
            vm.setModuleVisible(preview, true)
            assertFalse(vm.state.value.activeWorkspace.pose!!.authoringPose)
            assertEquals(0.75f, vm.state.value.parameterValues[parameter])
        }
    }

    @Test fun sliderSamplesMoveEveryViewAtOnceAndCommitOnlyOnRelease() = runBlocking<Unit> {
        fixture { vm, workspace ->
            vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            vm.setStateForTest(vm.state.value.copy(sdkStatus = "ready"))
            assertEquals(CanvasMode.EDIT, vm.state.value.activeCanvas.mode)
            val edit = vm.state.value.activeCanvas.id
            fun committed() = workspace.previewSession().getValue("values").jsonObject.getValue(parameter.raw).jsonPrimitive.float
            vm.beginParameterScrub()
            vm.setParameterValue(parameter, 0.25f)
            vm.setParameterValue(parameter, 0.5f)
            // The edit canvas, guides and physics read the authored pose; each sample reaches it at once.
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.5f, vm.canvasPose(vm.canvasEditorFor(edit).state)[parameter])
            assertEquals(0.5f, vm.parameterScrubValueOf(parameter))
            assertEquals(0f, committed(), "samples are not committed")
            assertFalse(vm.state.value.poseCommitBusy)
            vm.endParameterScrub()
            settled(vm)
            assertFalse(vm.parameterScrubActive)
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.5f, committed())
        }
    }

    @Test fun aCancelledSliderReturnsEveryViewToTheCommittedPose() = runBlocking<Unit> {
        fixture { vm, workspace ->
            vm.setParameterValue(parameter, 0.2f); settled(vm)
            val before = workspace.history().nodes.size
            vm.beginParameterScrub()
            vm.setParameterValue(parameter, 0.9f)
            assertEquals(0.9f, vm.state.value.parameterValues[parameter])
            vm.cancelParameterScrub()
            settled(vm)
            assertEquals(0.2f, vm.state.value.parameterValues[parameter])
            assertEquals(0.2f, workspace.previewSession().getValue("values").jsonObject.getValue(parameter.raw).jsonPrimitive.float)
            assertEquals(before, workspace.history().nodes.size)
        }
    }

    @Test fun theOpenMotionPosesTheSlidersAtThePlayheadWithoutAPreviewCanvas() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", duration = 1f, loop = true, curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f, io.github.psd2live.core.MotionInterpolation.LINEAR), MotionKey(1f, 1f, io.github.psd2live.core.MotionInterpolation.LINEAR)))))
            putClip(workspace, clip)
            assertTrue(vm.state.value.activeWorkspace.canvases.all { it.mode == CanvasMode.EDIT })
            vm.openMotionInEditor(clip.id)
            vm.setMotionPlayhead(0.75f)
            // The sliders read livePose over the authored pose, as the edit canvases read the clock frame.
            assertEquals(0.5f, vm.livePose.value[parameter])
            assertEquals(0.5f, vm.canvasPose(vm.state.value)[parameter])
            assertNull(vm.livePose.value[ParameterId("untracked")], "parameters without a curve keep the authored pose")
            // Playing with no preview on screen still advances the clock and the sliders with it.
            vm.setMotionPlayhead(0f)
            vm.setMotionEditorPlaying(true)
            withTimeout(5000) { while ((vm.livePose.value[parameter] ?: -1f) < -0.5f) kotlinx.coroutines.delay(10) }
            assertEquals(vm.livePose.value[parameter], vm.canvasPose(vm.state.value)[parameter])
            vm.setMotionEditorPlaying(false)
            vm.closeMotionEditorClip()
            assertNull(vm.livePose.value[parameter])
            withTimeout(5000) { while (vm.canvasPose(vm.state.value)[parameter] != 0f) kotlinx.coroutines.delay(10) }
        }
    }

    @Test fun previewSliderFlushCommitsTheLastClampedPair() = runBlocking<Unit> {
        fixture { vm, workspace ->
            vm.setStateForTest(vm.state.value.copy(sdkStatus = "ready"))
            vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
            vm.beginParameterScrub()
            vm.setParameterValues(mapOf(parameter to 0.6f, ParameterId("untracked") to 99f))
            vm.flushEditorFields()
            settled(vm)
            assertEquals(0.6f, vm.state.value.parameterValues[parameter])
            assertEquals(10f, vm.state.value.parameterValues[ParameterId("untracked")])
            assertFalse(vm.parameterScrubActive)
            vm.endParameterScrub()
        }
    }

    @Test fun scrubbingWithoutPreviewCanvasUpdatesEditPoseAndKeyingUsesTheLatestEdit() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f, io.github.psd2live.core.MotionInterpolation.LINEAR), MotionKey(1f, 1f, io.github.psd2live.core.MotionInterpolation.LINEAR)))))
            putClip(workspace, clip)
            val first = vm.state.value.activeCanvas.id
            val second = vm.addCanvas(CanvasMode.EDIT, focus = false)
            vm.openMotionInEditor(clip.id)
            vm.setMotionPlayhead(0.75f)
            assertEquals(0.5f, vm.canvasPose(vm.canvasEditorFor(first).state)[parameter])
            assertEquals(0.5f, vm.canvasPose(vm.canvasEditorFor(second).state)[parameter])
            assertEquals(0f, workspace.previewSession().getValue("values").jsonObject.getValue(parameter.raw).jsonPrimitive.float)
            assertEquals(2, vm.state.value.activeWorkspace.canvases.size)
            assertTrue(vm.state.value.activeWorkspace.canvases.all { it.mode == CanvasMode.EDIT })
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, first) {
                it.copy(previewParameterValues = mapOf(parameter to -0.8f))
            }
            vm.setParameterValue(parameter, 0.25f)
            settled(vm)
            vm.keyCurrentPose()
            settled(vm)
            assertEquals(0.25f, vm.editingMotionClip()!!.curve(parameter.raw)!!.keys.single { it.time == 0.75f }.value)
        }
    }

    @Test fun modelChangesNormalizeEveryWorkspaceAndCleanMotionTracks() = runBlocking<Unit> {
        fixture { vm, workspace ->
            putClip(workspace, MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw, listOf(MotionKey(0f, 0.9f))))))
            vm.setParameterValue(parameter, 0.9f)
            settled(vm)
            val first = vm.state.value.activeWorkspace.id
            vm.addWorkspace()
            vm.setParameterValue(parameter, -0.9f)
            settled(vm)
            val added = ParameterId("new")
            workspace.applyDocumentEdits(workspace.snapshot().state, "Parameter ranges", listOf(
                WorkspaceDocumentOperation("parameter_update", buildJsonObject { put("parameter_id", parameter.raw); put("min", -0.5); put("max", 0.5) }),
                WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", added.raw); put("name", "New"); put("min", 0); put("max", 1); put("default", 0.3) })
            ), MutationAuthor.USER)
            assertEquals(-0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.3f, vm.state.value.parameterValues[added])
            vm.setActiveWorkspace(first)
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertEquals(0.3f, vm.state.value.parameterValues[added])
            assertEquals(0.5f, vm.motionClips.single().curves.single().keys.single().value)
            workspace.applyDocumentEdits(workspace.snapshot().state, "Delete axis", listOf(WorkspaceDocumentOperation("parameter_delete",
                buildJsonObject { put("parameter_id", parameter.raw) })), MutationAuthor.USER)
            assertTrue(vm.motionClips.single().curves.isEmpty())
            assertTrue(vm.state.value.workspaces.all { parameter !in it.pose!!.parameterValues })
        }
    }

    @Test fun nonFocusedAndHiddenCanvasWritesReachEveryModeWithoutChangingSelection() = runBlocking<Unit> {
        fixture { vm, workspace ->
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

    @Test fun workspaceSwitchAndPersistenceKeepIndependentWorkspacePoses() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val firstWorkspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(parameter, 0.25f)
            settled(vm)
            vm.addWorkspace()
            val secondWorkspace = vm.state.value.activeWorkspace.id
            vm.setParameterValue(parameter, -0.75f)
            settled(vm)
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

    @Test fun authoringDropsStalePreviewValuesWithoutAddingLocks() = runBlocking<Unit> {
        fixture { vm, workspace ->
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, vm.state.value.activeCanvas.id) {
                it.copy(previewParameterValues = mapOf(parameter to -1f), animationEnabled = true)
            }
            vm.setParameterValue(parameter, 0.5f)
            settled(vm)
            assertFalse(vm.state.value.animationEnabled)
            assertEquals(0.5f, vm.state.value.previewParameterValues[parameter])
            assertEquals(0.5f, workspace.previewSession().getValue("values").jsonObject.getValue(parameter.raw).jsonPrimitive.float)
            assertTrue(vm.state.value.activeWorkspace.pose!!.authoringPose)
            vm.resetParameter(parameter)
            settled(vm)
            assertTrue(vm.state.value.lockedParameters.isEmpty())
        }
    }

    @Test fun animationAndToolsNeverRevealOrCreateMissingCanvases() = runBlocking<Unit> {
        fixture { vm, workspace ->
            vm.addWorkspace(WorkspacePreset.BLANK)
            val before = vm.state.value.activeWorkspace
            vm.ensureEditCanvas()
            vm.setAnimationEnabled(true)
            vm.setMouseTrackingEnabled(true)
            vm.triggerMotion("Blink")
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw, listOf(MotionKey(0f, 1f)))))
            putClip(workspace, clip)
            vm.openMotionInEditor(clip.id)
            vm.setMotionPlayhead(0f)
            val after = vm.state.value.activeWorkspace
            assertEquals(before.canvases.map { it.id to it.mode }, after.canvases.map { it.id to it.mode })
            assertEquals(before.hiddenModules, after.hiddenModules)
            assertEquals(before.activeCanvasId, after.activeCanvasId)
        }
    }

    @Test fun autoKeyingWhenEnabledAutomaticallyInsertsKeyframe() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f), MotionKey(1f, 1f)))))
            putClip(workspace, clip)
            vm.openMotionInEditor(clip.id)
            vm.setMotionAutoKey(true)
            vm.setMotionPlayhead(0.5f)

            vm.setParameterValue(parameter, 0.4f)
            settled(vm)

            val updatedCurve = vm.editingMotionClip()!!.curve(parameter.raw)!!
            val key = updatedCurve.keys.firstOrNull { it.time == 0.5f }
            assertNotNull(key)
            assertEquals(0.4f, key.value)
        }
    }

    @Test fun parameterChangeWithoutAutoKeyDoesNotAlterClip() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f), MotionKey(1f, 1f)))))
            putClip(workspace, clip)
            vm.openMotionInEditor(clip.id)
            vm.setMotionAutoKey(false)
            vm.setMotionPlayhead(0.5f)

            val keysBefore = vm.editingMotionClip()!!.curve(parameter.raw)!!.keys
            vm.setParameterValue(parameter, 0.9f)
            settled(vm)
            val keysAfter = vm.editingMotionClip()!!.curve(parameter.raw)!!.keys

            assertEquals(keysBefore, keysAfter)
        }
    }

    @Test fun autoKeyingUntrackedParameterArchivesInitialStateAtZero() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, 0f)))))
            putClip(workspace, clip)
            vm.openMotionInEditor(clip.id)
            vm.setMotionAutoKey(true)
            vm.setMotionPlayhead(0.8f)

            val untrackedId = ParameterId("untracked")
            vm.setParameterValue(untrackedId, 6f)
            settled(vm)

            val curve = vm.editingMotionClip()!!.curve("untracked")
            assertNotNull(curve)
            assertEquals(listOf(0f, 0.8f), curve.keys.map { it.time })
            assertEquals(listOf(2f, 6f), curve.keys.map { it.value })
            assertEquals(listOf(io.github.psd2live.core.MotionInterpolation.BEZIER, io.github.psd2live.core.MotionInterpolation.BEZIER), curve.keys.map { it.interpolation })
        }
    }

    @Test fun resetParameterWithAutoKeyRecordsDefaultValue() = runBlocking<Unit> {
        fixture { vm, workspace ->
            val clip = MotionClip("clip", "Clip", curves = listOf(MotionCurve(parameter.raw,
                listOf(MotionKey(0f, -1f), MotionKey(1f, 1f)))))
            putClip(workspace, clip)
            vm.openMotionInEditor(clip.id)
            vm.setMotionAutoKey(true)
            vm.setMotionPlayhead(0.2f)

            vm.setParameterValue(parameter, 0.8f)
            settled(vm)
            assertEquals(0.8f, vm.editingMotionClip()!!.curve(parameter.raw)!!.keys.first { it.time == 0.2f }.value)

            vm.resetParameter(parameter)
            settled(vm)
            assertEquals(0f, vm.editingMotionClip()!!.curve(parameter.raw)!!.keys.first { it.time == 0.2f }.value)
        }
    }
}
