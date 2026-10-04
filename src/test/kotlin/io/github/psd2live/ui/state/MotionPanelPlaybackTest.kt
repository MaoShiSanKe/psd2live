package io.github.psd2live.ui.state

import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionPresets
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPanelPlaybackTest {
	private val nod = MotionEditorState.presetClipId("Nod")
	@TempDir lateinit var temporary: Path
	private suspend fun settled(vm: PSD2LiveViewModel) = withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
	private suspend fun fixture(action: suspend (PSD2LiveViewModel) -> Unit) {
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
				assertNotNull(workspace.currentPuppet()!!.parameters.firstOrNull { it.id.raw == "ParamAngleY" })
				action(vm)
			}
		}
	}

	@Test fun panelPlayAndEditorPlayShareOnePlayback() = runBlocking<Unit> {
		fixture { vm ->
			vm.toggleMotionPlayback(nod)
			assertEquals(nod, vm.motionEditor.clipId)
			assertTrue(vm.motionEditor.playing)
			assertEquals("Nod", vm.editingMotionClip()?.builtin)
			// The editor's button pauses what the panel started, and the panel resumes it.
			vm.setMotionEditorPlaying(false)
			vm.toggleMotionPlayback(nod)
			assertTrue(vm.motionEditor.playing)
			vm.toggleMotionPlayback(nod)
			assertFalse(vm.motionEditor.playing)
			// Opening a preset never edits it.
			assertTrue(vm.state.value.rigEdits.motionClips.isEmpty())
		}
	}

	@Test fun editingAPresetKeyCreatesItsOverrideInOneStep() = runBlocking<Unit> {
		fixture { vm ->
			vm.editBuiltinMotion("Nod")
			vm.setMotionKey("ParamAngleY", 0.3f, -5f)
			settled(vm)
			val override = assertNotNull(MotionClips.overrideOf(vm.state.value.rigEdits.motionClips, "Nod"))
			assertEquals(1, vm.state.value.rigEdits.motionClips.size)
			assertEquals(nod, vm.motionEditor.clipId)
			assertEquals(override, vm.editingMotionClip())
			vm.setMotionKey("ParamAngleY", 0.4f, -6f)
			settled(vm)
			assertEquals(1, vm.state.value.rigEdits.motionClips.size)
			assertEquals(override.id, vm.editingMotionClip()?.id)
		}
	}

	@Test fun presetSettingsRetuneTheEditorClipAndDeletionRemovesIt() = runBlocking<Unit> {
		fixture { vm ->
			vm.editBuiltinMotion("Nod")
			val before = vm.editingMotionClip()!!
			vm.setMotionPresetValue("Nod", MotionPresets.AMPLITUDE, 0.5f)
			settled(vm)
			val after = vm.editingMotionClip()!!
			fun dip(clip: io.github.psd2live.core.MotionClip) = clip.curve("ParamAngleY")!!.keys.minOf { it.value }
			assertEquals(dip(before) * 0.5f, dip(after), 1e-4f)
			vm.setMotionPresetValue("Nod", MotionPresets.COUNT, 2f)
			settled(vm)
			assertTrue(vm.editingMotionClip()!!.duration > after.duration)

			vm.setMotionKey("ParamAngleY", 0.3f, -5f)
			settled(vm)
			vm.deleteMotionPreset("Nod")
			settled(vm)
			assertNull(vm.motionEditor.clipId)
			assertTrue(vm.state.value.rigEdits.motionPresets.getValue("Nod").deleted)
			assertNull(MotionClips.overrideOf(vm.state.value.rigEdits.motionClips, "Nod"))
			assertNull(vm.presetMotionClip(vm.state.value, "Nod"))

			vm.restoreMotionPreset("Nod")
			settled(vm)
			assertNotNull(vm.presetMotionClip(vm.state.value, "Nod"))
			assertFalse("Nod" in vm.state.value.rigEdits.motionPresets)
		}
	}
}
