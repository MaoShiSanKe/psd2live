package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkeletonAuthoringTest {
    private fun chain() = SkeletonSpec().withCustomBone(0f, 0f, 10f, 20f)
        .withCustomBone(10f, 20f, 30f, 40f, "custom_1")
        .withCustomBone(30f, 40f, 50f, 60f, "custom_2")

    @Test fun selectingAChainTransformsEveryBoneOnlyOnce() {
        val original = chain()
        val moved = original.withBonesTransformed(setOf("custom_1", "custom_2"), 7f, -5f, includeDescendants = true)
        original.bones.zip(moved.bones).forEach { (a, b) ->
            assertEquals(a.headX + 7f, b.headX); assertEquals(a.tailY - 5f, b.tailY)
            assertEquals(a.parameterId, b.parameterId)
        }
        assertEquals(original, original.withBonesTransformed(emptySet(), 7f))
    }

    @Test fun connectedExternalEndpointsFollowSelectionIncludingSiblings() {
        val original = chain().withCustomBone(10f, 20f, -20f, 50f, "custom_1")
        val moved = original.withBonesTransformed(setOf("custom_2"), 7f, 8f)
        assertEquals(17f, moved.bone("custom_1")!!.tailX)
        assertEquals(17f, moved.bone("custom_4")!!.headX)
        assertEquals(-20f, moved.bone("custom_4")!!.tailX)
        assertTrue(moved.isConnected("custom_3"))
        val detached = original.withBoneParent("custom_4", "custom_1", false).withBonesTransformed(setOf("custom_2"), 7f, 8f)
        assertEquals(10f, detached.bone("custom_4")!!.headX)
    }

    @Test fun rotationAndScalingUseTheSelectionCenter() {
        val original = SkeletonSpec().withCustomBone(-10f, 0f, 10f, 0f)
        val moved = original.withBonesTransformed(setOf("custom_1"), degrees = 90f, scale = 2f)
        assertTrue(kotlin.math.abs(moved.bones.single().headX) < 0.001f)
        assertEquals(-20f, moved.bones.single().headY)
        assertEquals(20f, moved.bones.single().tailY)
        assertFailsWith<IllegalArgumentException> { original.withBonesTransformed(setOf("custom_1"), scale = 0f) }
    }

    @Test fun boxSelectionFindsCrossingSegmentsAndExcludesNearMisses() {
        val spec = SkeletonSpec().withCustomBone(-10f, 0f, 10f, 0f).withCustomBone(-10f, 10f, 10f, 10f)
        assertEquals(setOf("custom_1"), spec.bonesInBox(-1f, -1f, 1f, 1f))
        assertEquals(setOf("custom_1"), spec.bonesInBox(1f, 1f, -1f, -1f))
        assertTrue(spec.bonesInBox(30f, 30f, 40f, 40f).isEmpty())
    }

    @Test fun renameKeepsIdentityParametersAndBindings() {
        val original = chain()
        val renamed = original.withBoneRenamed("custom_2", " elbow ")
        assertEquals(original.bones[1].copy(name = "elbow"), renamed.bones[1])
        assertEquals(original.bones[1].parameterId, renamed.bones[1].parameterId)
        assertFailsWith<IllegalArgumentException> { original.withBoneRenamed("custom_2", " ") }
    }

    @Test fun reparentPreservesPositionOrTranslatesTheEntireSubtree() {
        val original = chain().withCustomBone(100f, 100f, 200f, 200f)
        val moved = original.withBoneParent("custom_2", "custom_4", true)
        assertEquals(200f, moved.bone("custom_2")!!.headX)
        assertEquals(200f, moved.bone("custom_2")!!.headY)
        assertEquals(240f, moved.bone("custom_3")!!.tailX)
        assertEquals(240f, moved.bone("custom_3")!!.tailY)
        val independent = moved.withBoneParent("custom_2", null)
        assertEquals(moved.bone("custom_2")!!.headX, independent.bone("custom_2")!!.headX)
        assertNull(independent.bone("custom_2")!!.parentId)
        assertFailsWith<IllegalArgumentException> { original.withBoneParent("custom_1", "custom_3") }
        assertFailsWith<IllegalArgumentException> { original.withBoneParent("custom_1", "custom_1") }
    }

    @Test fun explicitlyDisconnectedCoincidentJointsMoveIndependentlyAndPersist() {
        val original = chain().withBoneParent("custom_2", "custom_1", false)
        assertEquals(false, original.isConnected("custom_2"))
        val moved = original.withJointMoved("custom_2", BoneEnd.HEAD, 99f, 99f)
        assertEquals(10f, moved.bone("custom_1")!!.tailX)
        val parentMoved = original.withJointMoved("custom_1", BoneEnd.TAIL, 99f, 99f)
        assertEquals(10f, parentMoved.bone("custom_2")!!.headX)
        assertEquals(original, SkeletonSpec.fromJson(original.toJson()))
        val connected = original.withBoneParent("custom_2", "custom_1", true)
        assertTrue(connected.isConnected("custom_2"))
        assertEquals(99f, connected.withJointMoved("custom_1", BoneEnd.TAIL, 99f, 99f).bone("custom_2")!!.headX)
    }
    @Test fun independentBonesHaveUniqueIdsAndNoInheritedBindings() {
        val first = SkeletonSpec().withCustomBone(10f, 20f, 30f, 40f)
        val next = first.withCustomBone(-10f, -20f, -30f, -40f)
        assertEquals(listOf("custom_1", "custom_2"), next.bones.map { it.id })
        assertNull(next.bones.last().parentId)
        assertEquals(-10f, next.bones.last().headX)
        assertEquals(-40f, next.bones.last().tailY)
        assertTrue(next.bones.all { it.drawableIds.isEmpty() && it.role == BoneRole.CUSTOM })
        assertEquals(next, SkeletonSpec.fromJson(next.toJson()))
    }

    @Test fun extrusionConnectsToTheParentAndCanContinueAsAChain() {
        val root = SkeletonSpec().withCustomBone(0f, 0f, 10f, 20f)
        val initial = root.withBone(root.bones.single().copy(drawableIds = listOf("mesh")))
        val child = initial.withCustomBone(99f, 99f, 30f, 40f, "custom_1")
        val chain = child.withCustomBone(99f, 99f, 50f, 60f, "custom_2")
        assertEquals("custom_1", child.bones.last().parentId)
        assertEquals(10f, child.bones.last().headX)
        assertEquals(20f, child.bones.last().headY)
        assertTrue(child.bones.last().drawableIds.isEmpty())
        assertEquals(listOf("mesh"), child.bones.first().drawableIds)
        assertEquals("custom_2", chain.bones.last().parentId)
        assertEquals(30f, chain.bones.last().headX)
        val moved = chain.withJointMoved("custom_1", BoneEnd.TAIL, 11f, 22f)
        assertEquals(11f, moved.bone("custom_2")!!.headX)
        assertEquals(22f, moved.bone("custom_2")!!.headY)
        assertEquals(initial.bones, initial.topological())
    }

    @Test fun invalidOrZeroLengthCreationDoesNotProduceBrokenBones() {
        val initial = SkeletonSpec().withCustomBone(0f, 0f, 10f, 20f)
        assertEquals(initial, initial.withCustomBone(2f, 3f, 2f, 3f))
        assertEquals(initial, initial.withCustomBone(99f, 99f, 10f, 20f, "custom_1"))
        assertFailsWith<IllegalArgumentException> { initial.withCustomBone(0f, 0f, Float.NaN, 1f) }
        assertFailsWith<IllegalArgumentException> { initial.withCustomBone(0f, 0f, 1f, 1f, "missing") }
    }
}
