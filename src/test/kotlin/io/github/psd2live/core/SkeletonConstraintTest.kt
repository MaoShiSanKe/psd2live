package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.*

class SkeletonConstraintTest {
    private val model = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
    private fun chain() = SkeletonSpec().withCustomBone(0f, 0f, 0f, 50f)
        .withCustomBone(0f, 50f, 0f, 100f, "custom_1")

    @Test fun chainLengthSettingsRestrictWhichParentsTheSolverCanChange() {
        val original = chain()
        val spec = original.withBone(original.bones.last().copy(ik = SkeletonIkSettings(chainLength = 1)))
        val values = SkeletonPoseSolver.drag(spec, SkeletonPoseSolver.posed(model, spec, emptyMap()),
            BoneHit("custom_2", true), 40f, 70f, emptyMap(), true)
        assertEquals(setOf(ParameterId(spec.bones.last().parameterId)), values.keys)
    }

    @Test fun bendDirectionResolvesStraightChainSingularitiesInEitherDirection() {
        for (direction in listOf(-1, 1)) {
            val original = chain()
            val spec = original.withBone(original.bones.last().copy(ik = SkeletonIkSettings(iterations = 256, tolerancePx = 0.01f, bendDirection = direction)))
            val values = SkeletonPoseSolver.drag(spec, SkeletonPoseSolver.posed(model, spec, emptyMap()),
                BoneHit("custom_2", true), 0f, 80f, emptyMap(), true)
            val posed = SkeletonPoseSolver.posed(model, spec, values)
            assertTrue(kotlin.math.hypot(posed.last().tailX, posed.last().tailY - 80f) < 0.1f,
                "Direction $direction, values $values, tip ${posed.last().tailX}, ${posed.last().tailY}")
            val angle = SkeletonIk.wrap(SkeletonIk.heading((posed.last().tailX - posed.last().headX).toDouble(), (posed.last().tailY - posed.last().headY).toDouble()) -
                SkeletonIk.heading((posed.first().tailX - posed.first().headX).toDouble(), (posed.first().tailY - posed.first().headY).toDouble()))
            assertTrue(angle * direction > 0)
        }
    }

    @Test fun fixedTargetsRemainSatisfiedAfterChangingAParentParameter() {
        val spec = chain().withIkTarget("custom_2", SkeletonIkTarget(40f, 70f))
        val edited = mapOf(ParameterId(spec.bones.first().parameterId) to 30f)
        val solved = edited + SkeletonPoseSolver.solveTargets(model, spec, edited)
        val tip = SkeletonPoseSolver.posed(model, spec, solved).last()
        assertTrue(kotlin.math.hypot(tip.tailX - 40f, tip.tailY - 70f) < 0.1f)
        val inactive = spec.withIkTarget("custom_2", SkeletonIkTarget(40f, 70f, false))
        assertTrue(SkeletonPoseSolver.solveTargets(model, inactive, edited).isEmpty())
    }

    @Test fun unreachableTargetsStayFiniteAndRespectJointLimits() {
        val spec = chain().withIkTarget("custom_2", SkeletonIkTarget(500f, 500f))
        val solved = SkeletonPoseSolver.solveTargets(model, spec, emptyMap())
        assertTrue(solved.values.all(Float::isFinite))
        for (bone in spec.bones) solved[ParameterId(bone.parameterId)]?.let { assertTrue(it in bone.minAngle..bone.maxAngle) }
        val tip = SkeletonPoseSolver.posed(model, spec, solved).last()
        assertTrue(kotlin.math.hypot(tip.tailX - 500f, tip.tailY - 500f) > 100f)
    }

    @Test fun constraintsPersistAndFollowStructuralChangesWithoutDanglingTargets() {
        val original = chain().withIkTarget("custom_2", SkeletonIkTarget(40f, 70f))
        assertEquals(original, SkeletonSpec.fromJson(original.toJson()))
        assertTrue(original.withoutBone("custom_2").ikTargets.isEmpty())
        val split = SkeletonAuthoring.subdivide(original, "custom_2", 2)
        assertEquals(split.selected.last(), split.spec.ikTargets.keys.single())
        val merged = SkeletonAuthoring.dissolve(original, "custom_2")
        assertEquals(setOf("custom_1"), merged.spec.ikTargets.keys)
        val mirrored = SkeletonAuthoring.duplicate(original, setOf("custom_2"), mirrorAxis = 0f)
        assertEquals(-40f, mirrored.spec.ikTargets.getValue(mirrored.selected.single()).x)
        assertFailsWith<IllegalArgumentException> { SkeletonIkSettings(chainLength = 0) }
        assertFailsWith<IllegalArgumentException> { SkeletonIkSettings(tolerancePx = Float.NaN) }
    }
}
