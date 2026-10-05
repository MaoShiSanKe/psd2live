package io.github.psd2live.core

import kotlin.test.*

class SkeletonStructuralTest {
    private fun chain(): SkeletonSpec {
        val spec = SkeletonSpec().withCustomBone(10f, 0f, 10f, 40f)
            .withCustomBone(10f, 40f, 10f, 80f, "custom_1")
            .withCustomBone(10f, 80f, 10f, 100f, "custom_2")
        return spec.withBone(spec.bones[0].copy(role = BoneRole.UPPER_ARM, side = Side.LEFT, drawableIds = listOf("left")))
    }

    @Test fun duplicateRetainsHierarchySettingsAndUsesIndependentParameterIds() {
        val original = chain()
        val result = SkeletonAuthoring.duplicate(original, setOf("custom_1"), true)
        val copies = result.spec.bones.filter { it.id in result.selected }
        assertEquals(3, copies.size)
        assertEquals(copies[0].id, copies[1].parentId)
        assertEquals(copies[1].id, copies[2].parentId)
        assertEquals(BoneRole.UPPER_ARM, copies[0].role)
        assertEquals(original.bones[0].minAngle, copies[0].minAngle)
        assertEquals(30f, copies[0].headX)
        assertTrue(copies.all { it.drawableIds.isEmpty() })
        assertEquals(6, result.spec.bones.map { it.parameterId }.distinct().size)
        assertEquals(result.spec, SkeletonSpec.fromJson(result.spec.toJson()))
        val transfer = SkeletonAuthoring.duplicate(original, setOf("custom_1"), transferBindings = true)
        assertTrue(transfer.spec.bone("custom_1")!!.drawableIds.isEmpty())
        assertEquals(listOf("left"), transfer.spec.bones.last().drawableIds)
    }

    @Test fun mirrorChangesSideDirectionCoordinatesAndMatchesBindingsWithoutDuplicateOwners() {
        val original = chain().withCustomBone(-10f, 0f, -10f, 40f)
            .withDrawableBound("right", "custom_4")
        val result = SkeletonAuthoring.duplicate(original, setOf("custom_1"), true,
            mirrorAxis = 0f, drawableMirrors = mapOf("left" to "right"))
        val mirrored = result.spec.bones.filter { it.id in result.selected }
        assertEquals(-10f, mirrored[0].headX)
        assertEquals(Side.RIGHT, mirrored[0].side)
        assertEquals(-1f, mirrored[0].direction)
        assertEquals(listOf("right"), mirrored[0].drawableIds)
        assertTrue(result.spec.bone("custom_4")!!.drawableIds.isEmpty())
        assertEquals("custom_1", mirrored[0].mirrorId)
        assertEquals(mirrored[0].id, result.spec.bone("custom_1")!!.mirrorId)
        assertEquals(0f, result.spec.symmetryAxisX)
        val moved = result.spec.withJointMoved("custom_2", BoneEnd.TAIL, 30f, 75f)
        val symmetric = SkeletonAuthoring.synchronizeMirrors(moved, setOf("custom_2"))
        assertEquals(-30f, symmetric.bone(mirrored[1].id)!!.tailX)
        assertEquals(75f, symmetric.bone(mirrored[1].id)!!.tailY)
        assertTrue(symmetric.isConnected(mirrored[2].id))
    }

    @Test fun repeatedMirrorAndDeletionLeaveNoDanglingPartnerReferences() {
        val mirrored = SkeletonAuthoring.duplicate(chain(), setOf("custom_1"), mirrorAxis = 0f).spec
        val oldPartner = mirrored.bone("custom_1")!!.mirrorId!!
        val again = SkeletonAuthoring.duplicate(mirrored, setOf("custom_1"), mirrorAxis = 0f).spec
        assertNull(again.bone(oldPartner)!!.mirrorId)
        val deleted = again.withoutBone("custom_1")
        assertTrue(deleted.bones.none { it.mirrorId == "custom_1" })
    }

    @Test fun subdivisionPreservesTheFirstIdBindingAndReparentsOriginalChildrenAtTheLastTip() {
        val original = chain()
        val split = SkeletonAuthoring.subdivide(original, "custom_1", 4)
        val pieces = split.spec.topological().filter { it.id in split.selected }
        assertEquals(4, pieces.size)
        assertEquals("custom_1", pieces[0].id)
        assertEquals(listOf("left"), pieces[0].drawableIds)
        assertEquals(original.bones[0].parameterId, pieces[0].parameterId)
        assertTrue(pieces.all { kotlin.math.abs(it.length - 10f) < 0.001f })
        assertEquals(pieces.last().id, split.spec.bone("custom_2")!!.parentId)
        assertTrue(split.spec.isConnected("custom_2"))
        assertEquals(split.spec, SkeletonSpec.fromJson(split.spec.toJson()))
    }

    @Test fun dissolvePreservesGrandchildrenAndTransfersBindings() {
        val original = chain().withDrawableBound("childMesh", "custom_2")
        val result = SkeletonAuthoring.dissolve(original, "custom_2")
        assertNull(result.spec.bone("custom_2"))
        assertEquals(80f, result.spec.bone("custom_1")!!.tailY)
        assertEquals(listOf("left", "childMesh"), result.spec.bone("custom_1")!!.drawableIds)
        assertEquals("custom_1", result.spec.bone("custom_3")!!.parentId)
        assertTrue(result.spec.isConnected("custom_3"))
        assertEquals(setOf("custom_1"), result.selected)
        val branch = original.withCustomBone(10f, 40f, 40f, 70f, "custom_1")
        assertFalse(SkeletonAuthoring.canDissolve(branch, "custom_2"))
        assertFalse(SkeletonAuthoring.canDissolve(original.withBoneParent("custom_2", "custom_1", false), "custom_2"))
    }
}
