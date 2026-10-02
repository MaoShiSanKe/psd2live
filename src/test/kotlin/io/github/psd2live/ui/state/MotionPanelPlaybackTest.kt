package io.github.psd2live.ui.state

import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPanelPlaybackTest {
	private val nod = MotionEditorState.presetClipId("Nod")

	@Test fun panelPlayAndEditorPlayShareOnePlayback() {
		PSD2LiveViewModel().use { vm ->
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

	@Test fun editingAPresetKeyCreatesItsOverrideInOneStep() {
		PSD2LiveViewModel().use { vm ->
			vm.editBuiltinMotion("Nod")
			vm.setMotionKey("ParamAngleY", 0.3f, -5f)
			val override = assertNotNull(MotionClips.overrideOf(vm.state.value.rigEdits.motionClips, "Nod"))
			assertEquals(1, vm.state.value.rigEdits.motionClips.size)
			assertEquals(nod, vm.motionEditor.clipId)
			assertEquals(override, vm.editingMotionClip())
			vm.setMotionKey("ParamAngleY", 0.4f, -6f)
			assertEquals(1, vm.state.value.rigEdits.motionClips.size)
			assertEquals(override.id, vm.editingMotionClip()?.id)
		}
	}

	@Test fun presetSettingsRetuneTheEditorClipAndDeletionRemovesIt() {
		PSD2LiveViewModel().use { vm ->
			vm.editBuiltinMotion("Nod")
			val before = vm.editingMotionClip()!!
			vm.setMotionPresetValue("Nod", MotionPresets.AMPLITUDE, 0.5f)
			val after = vm.editingMotionClip()!!
			fun dip(clip: io.github.psd2live.core.MotionClip) = clip.curve("ParamAngleY")!!.keys.minOf { it.value }
			assertEquals(dip(before) * 0.5f, dip(after), 1e-4f)
			vm.setMotionPresetValue("Nod", MotionPresets.COUNT, 2f)
			assertTrue(vm.editingMotionClip()!!.duration > after.duration)

			vm.setMotionKey("ParamAngleY", 0.3f, -5f)
			vm.deleteMotionPreset("Nod")
			assertNull(vm.motionEditor.clipId)
			assertTrue(vm.state.value.rigEdits.motionPresets.getValue("Nod").deleted)
			assertNull(MotionClips.overrideOf(vm.state.value.rigEdits.motionClips, "Nod"))
			assertNull(vm.presetMotionClip(vm.state.value, "Nod"))

			vm.restoreMotionPreset("Nod")
			assertNotNull(vm.presetMotionClip(vm.state.value, "Nod"))
			assertFalse("Nod" in vm.state.value.rigEdits.motionPresets)
		}
	}
}
