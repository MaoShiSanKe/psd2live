package io.github.psd2live.core

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.skeletonDraftFixture
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.*

class SkeletonDraftEditsTest {
    private fun fixture(): Pair<SkeletonSpec, RigPreviewModel> = runBlocking {
        val capture = skeletonDraftFixture(WorkspaceRuntime({ WorkspacePreviewBuilder().build(it) }))
        requireNotNull(capture.document.rigEdits.skeleton) to capture.model
    }
    private fun apply(spec: SkeletonSpec, model: RigPreviewModel, vararg intents: SkeletonDraftIntent) =
        SkeletonDraftEdits.applyAll(spec, model, intents.toList())

    @Test fun structuralIntentsMatchTheAuthoringAlgorithmsAndReportTheirSelection() {
        val (spec, model) = fixture()
        val moved = apply(spec, model, SkeletonDraftIntent.Transform(setOf("arm_upper_l"), dx = 3f, dy = -2f, degrees = 10f, descendants = true))
        assertEquals(spec.withBonesTransformed(setOf("arm_upper_l"), 3f, -2f, 10f, 1f, true), moved.spec)
        assertNull(moved.selected)

        val mirrored = apply(spec, model, SkeletonDraftIntent.Duplicate(setOf("arm_upper_l"), descendants = true, mirror = true, suffix = " M"))
        val expected = SkeletonAuthoring.duplicate(spec, setOf("arm_upper_l"), true, mirrorAxis = 60f,
            drawableMirrors = SkeletonDraftEdits.mirrorDrawables(model), suffix = " M")
        assertEquals(expected.spec, mirrored.spec); assertEquals(expected.selected, mirrored.selected)
        assertEquals(mapOf("ArtMeshHandwearL" to "ArtMeshHandwearR", "ArtMeshHandwearR" to "ArtMeshHandwearL"), SkeletonDraftEdits.mirrorDrawables(model))
        val copy = mirrored.spec.bone(mirrored.selected!!.first { mirrored.spec.bone(it)!!.role == BoneRole.UPPER_ARM })!!
        assertEquals(listOf("ArtMeshHandwearR"), copy.drawableIds)
        assertEquals(120f - spec.bone("arm_upper_l")!!.tailX, copy.tailX, 1e-4f)
        assertEquals("arm_upper_l", copy.mirrorId); assertEquals(copy.id, mirrored.spec.bone("arm_upper_l")!!.mirrorId)

        // A symmetric move keeps the reflected partner on the other side of the axis.
        val symmetric = apply(mirrored.spec, model, SkeletonDraftIntent.MoveJoint("arm_upper_l", BoneEnd.TAIL, 100f, 30f, symmetric = true))
        assertEquals(setOf("arm_upper_l"), symmetric.selected)
        assertEquals(20f, symmetric.spec.bone(copy.id)!!.tailX, 1e-4f); assertEquals(30f, symmetric.spec.bone(copy.id)!!.tailY, 1e-4f)

        val split = apply(spec, model, SkeletonDraftIntent.Subdivide("arm_fore_l", 3))
        assertEquals(SkeletonAuthoring.subdivide(spec, "arm_fore_l", 3).spec, split.spec)
        assertEquals(3, split.selected!!.size)
        val child = split.spec.bones.single { it.parentId == "arm_fore_l" && it.id in split.selected }
        val merged = apply(split.spec, model, SkeletonDraftIntent.Dissolve(child.id))
        assertEquals(setOf("arm_fore_l"), merged.selected)
        assertEquals(SkeletonAuthoring.dissolve(split.spec, child.id).spec, merged.spec)

        val tailless = apply(spec, model, SkeletonDraftIntent.OptionalChain(BoneRole.TAIL, false))
        assertTrue(tailless.spec.bones.none { it.role == BoneRole.TAIL })
        val restored = apply(tailless.spec, model, SkeletonDraftIntent.OptionalChain(BoneRole.TAIL, true))
        // The template is proposed from the current rig, as the editor's chain toggle does.
        assertEquals(SkeletonAutoBuilder.build(model.analysis, model.rig).bones.filter { it.role == BoneRole.TAIL },
            restored.spec.bones.filter { it.role == BoneRole.TAIL })
        assertEquals(spec.bones.filter { it.role == BoneRole.TAIL }.map { it.id }, restored.spec.bones.filter { it.role == BoneRole.TAIL }.map { it.id })

        val created = apply(spec, model, SkeletonDraftIntent.CreateBone(0f, 0f, 30f, 40f, "hand_l"))
        val bone = created.spec.bone(created.selected!!.single())!!
        assertEquals("hand_l", bone.parentId); assertTrue(created.spec.isConnected(bone.id))
        assertEquals(setOf("hand_l"), apply(created.spec, model, SkeletonDraftIntent.RemoveBone(bone.id)).selected)
    }

    @Test fun manualWeightIntentsPaintCleanClearAndTransferWithTheSharedWeightCore() {
        val (spec, model) = fixture()
        val puppet = model.rig.puppet
        val target = "ArtMeshHandwearL"
        assertEquals(setOf("arm_upper_l", "arm_fore_l", "hand_l"), SkeletonManualWeights.treeIds(spec, target))
        val points = listOf(88f to 50f, 89f to 60f)
        val painted = apply(spec, model, SkeletonDraftIntent.PaintWeights(target, "arm_fore_l", points, 12f, 0.8f, SkeletonWeightBrushMode.ADD))
        val captured = SkeletonManualWeights.capture(spec, puppet, target)!!
        val expected = points.fold(captured) { map, (x, y) ->
            SkeletonManualWeights.paint(spec, map, "arm_fore_l", x, y, 12f, 0.8f, SkeletonWeightBrushMode.ADD) }
        assertEquals(expected, painted.spec.manualWeights[target])
        assertNotEquals(captured, expected)
        // Later dabs of one stroke continue on the stored map instead of resampling it.
        val continued = apply(painted.spec, model, SkeletonDraftIntent.PaintWeights(target, "arm_fore_l", listOf(90f to 70f), 12f, 0.8f,
            SkeletonWeightBrushMode.SUBTRACT, capture = false))
        assertEquals(SkeletonManualWeights.paint(spec, expected, "arm_fore_l", 90f, 70f, 12f, 0.8f, SkeletonWeightBrushMode.SUBTRACT),
            continued.spec.manualWeights[target])

        val cleaned = apply(painted.spec, model, SkeletonDraftIntent.CleanupWeights(target, 1, 0.01f))
        assertTrue(cleaned.spec.manualWeights.getValue(target).weights.all { it.size == 1 })
        assertNull(apply(cleaned.spec, model, SkeletonDraftIntent.ClearWeights(target)).spec.manualWeights[target])

        val transfer = SkeletonDraftIntent.TransferWeights(target, "ArtMeshHandwearR", SkeletonWeightTransferMode.NEAREST, 20f, mirror = true)
        assertEquals(mapOf("arm_upper_l" to "arm_upper_r", "arm_fore_l" to "arm_fore_r", "hand_l" to "hand_r"),
            SkeletonDraftEdits.weightMapping(painted.spec, target, "ArtMeshHandwearR", mirror = true))
        val preview = assertNotNull(SkeletonDraftEdits.transfer(painted.spec, model, transfer))
        assertTrue(preview.matched > 0)
        val copied = apply(painted.spec, model, transfer)
        assertEquals(preview.map, copied.spec.manualWeights["ArtMeshHandwearR"])
        assertTrue(copied.spec.manualWeights.getValue("ArtMeshHandwearR").weights.flatMap { it.keys }.all { it.endsWith("_r") })
        // An explicit pair overrides the inferred one.
        assertEquals("hand_r", SkeletonDraftEdits.weightMapping(painted.spec, target, "ArtMeshHandwearR",
            mapOf("arm_fore_l" to "hand_r"), mirror = true)["arm_fore_l"])
        assertNull(SkeletonDraftEdits.transfer(painted.spec, model, transfer.copy(targetId = "ArtMeshTail", mode = SkeletonWeightTransferMode.TOPOLOGY)))
    }

    @Test fun invalidIntentsFailWithTheirIndexAndNoPartialDraft() {
        val (spec, model) = fixture()
        val failure = assertFailsWith<IllegalStateException> {
            SkeletonDraftEdits.applyAll(spec, model, listOf(SkeletonDraftIntent.Transform(setOf("arm_upper_l"), dx = 5f),
                SkeletonDraftIntent.RemoveBone("upper_body"))) { index, error -> IllegalStateException("edit $index", error) }
        }
        assertEquals("edit 1", failure.message)
        for (invalid in listOf(SkeletonDraftIntent.Bind(setOf("missing"), "arm_upper_l"), SkeletonDraftIntent.Dissolve("arm_upper_l"),
            SkeletonDraftIntent.Transform(setOf("ghost"), dx = 1f), SkeletonDraftIntent.Subdivide("upper_body", 2),
            SkeletonDraftIntent.PaintWeights("ArtMeshHandwearL", "tail_1", listOf(1f to 1f), 4f, 0.5f, SkeletonWeightBrushMode.ADD),
            SkeletonDraftIntent.Rename("head", " "), SkeletonDraftIntent.Parent("upper_body", "head"),
            SkeletonDraftIntent.OptionalChain(BoneRole.HAND, true))) {
            assertFailsWith<IllegalArgumentException>(invalid.toString()) { apply(spec, model, invalid) }
        }
        val drawable = spec.bone("arm_upper_l")!!.drawableIds.single()
        val doubled = spec.copy(bones = spec.bones.map { if (it.id == "hand_l") it.copy(drawableIds = listOf(drawable)) else it })
        assertFailsWith<IllegalArgumentException> { apply(spec, model, SkeletonDraftIntent.Restore(doubled)) }
        val limits = apply(spec, model, SkeletonDraftIntent.Limits("arm_fore_l", -400f, 30f)).spec.bone("arm_fore_l")!!
        assertTrue(abs(limits.minAngle + 180f) < 1e-6f && limits.maxAngle == 30f)
    }
}
