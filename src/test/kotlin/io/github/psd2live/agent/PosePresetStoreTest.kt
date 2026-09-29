package io.github.psd2live.agent

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.PosePreset
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PosePresetStoreTest {
	@TempDir lateinit var temp: Path

	@Test fun poseSnapshotsAndBakedClipsRoundTripThroughTheStore() {
		val overlay = RigEditOverlay(
			motionClips = listOf(MotionClip("m", "Baked", curves = listOf(MotionCurve("ParamArmLA", listOf(MotionKey(0f, 1f), MotionKey(1f, 2f)))))),
			posePresets = listOf(PosePreset("lean", "Lean", mapOf("ParamArmLA" to 12.5f, "ParamArmLB" to -3f))),
		)
		val document = AgentWorkspaceDocument(WorkspaceSourceArt(8, 8, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay)
		val store = AgentWorkspaceStore(temp)
		store.persistHistory("poses", WorkspaceHistoryTree(document, "revision", "snapshot").state())
		val restored = assertNotNull(store.loadHistory("poses")).head().snapshot.rigEdits
		assertEquals(overlay.posePresets, restored.posePresets)
		assertEquals(overlay.motionClips, restored.motionClips)
	}

	@Test fun duplicateOrTooManyPosesAreRejected() {
		val pose = PosePreset("a", "A", mapOf("P" to 1f))
		assertFailsWith<IllegalArgumentException> { RigEditOverlay(posePresets = listOf(pose, pose.copy(name = "B"))) }
		assertFailsWith<IllegalArgumentException> {
			RigEditOverlay(posePresets = (0..PosePreset.MAX_POSES).map { PosePreset("p$it", "P$it", mapOf("P" to 1f)) })
		}
	}
}
