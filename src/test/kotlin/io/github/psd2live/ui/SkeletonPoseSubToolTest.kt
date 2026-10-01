package io.github.psd2live.ui

import io.github.psd2live.core.SkeletonSpec
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonPoseSubToolTest {
    @Test fun explicitFkIgnoresTipAndShiftWhileIkSolvesTheChain() {
        val spec = SkeletonSpec().withCustomBone(0f, 0f, 0f, 50f)
            .withCustomBone(0f, 50f, 0f, 100f, "custom_1")
        val posed = spec.bones.map { PosedBone(it, it.headX, it.headY, it.tailX, it.tailY) }
        fun drag(mode: SkeletonPoseSubTool, tip: Boolean, shift: Boolean) =
            SkeletonPoseTool.drag(spec, posed, BoneHit("custom_2", tip), 60f, 80f, emptyMap(), shift, mode)
        val child = ParameterId(spec.bones[1].parameterId)
        val chain = spec.bones.map { ParameterId(it.parameterId) }.toSet()
        assertEquals(setOf(child), drag(SkeletonPoseSubTool.FK, true, true).keys)
        assertEquals(chain, drag(SkeletonPoseSubTool.IK, false, false).keys)
        assertEquals(setOf(child), drag(SkeletonPoseSubTool.AUTO, false, false).keys)
        assertEquals(chain, drag(SkeletonPoseSubTool.AUTO, false, true).keys)
        assertEquals(chain, drag(SkeletonPoseSubTool.AUTO, true, false).keys)
    }
}
