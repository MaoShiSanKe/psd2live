package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkeletonAuthoringTest {
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
