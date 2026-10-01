package io.github.psd2live.core

import kotlin.test.*

class SkeletonManualWeightsTest {
    private fun spec() = SkeletonSpec().withCustomBone(0f, 0f, 0f, 50f)
        .withCustomBone(0f, 50f, 0f, 100f, "custom_1").withDrawableBound("mesh", "custom_1")
    private fun map(rows: List<Map<String, Float>> = List(3) { mapOf("custom_1" to 1f) }) =
        SkeletonWeightMap(listOf(0f, 0f, 10f, 0f, 0f, 10f), listOf(0, 1, 2), rows)

    @Test fun interpolationPersistsAndGivesNewVerticesTheSameSkinAsAuthoredSamples() {
        val spec = spec()
        val map = map(listOf(mapOf("custom_1" to 1f), mapOf("custom_2" to 1f), mapOf("custom_1" to 1f)))
        val sample = map.sample(10f / 3, 10f / 3)!!
        assertEquals(1f / 3, sample.getValue("custom_2"), 0.0001f)
        val tree = SkeletonRig.jointBones(spec)
        val canvas = floatArrayOf(10f / 3, 10f / 3, 100f, 100f)
        val skin = SkeletonManualWeights.weights(canvas, intArrayOf(), tree, SkeletonRig.jointParents(spec), map)
        assertEquals(0, skin[0].from); assertEquals(1, skin[0].to)
        assertEquals(1f / 3, skin[0].weight, 0.0001f)
        val automatic = SkeletonManualWeights.weights(canvas, intArrayOf(), tree, SkeletonRig.jointParents(spec), null)
        assertEquals(automatic[1].from, skin[1].from); assertEquals(automatic[1].weight, skin[1].weight)
        val saved = spec.withManualWeights("mesh", map)
        assertEquals(saved, SkeletonSpec.fromJson(saved.toJson()))
    }

    @Test fun addSubtractReplaceAndSmoothActuallyChangeWeightsAndRetainNormalization() {
        val spec = spec(); val original = map()
        val added = SkeletonManualWeights.paint(spec, original, "custom_2", 0f, 0f, 1f, 0.5f, SkeletonWeightBrushMode.ADD)
        assertEquals(0.5f, added.weights[0]["custom_2"])
        val subtracted = SkeletonManualWeights.paint(spec, added, "custom_2", 0f, 0f, 1f, 0.25f, SkeletonWeightBrushMode.SUBTRACT)
        assertEquals(0.25f, subtracted.weights[0]["custom_2"])
        val replaced = SkeletonManualWeights.paint(spec, added, "custom_2", 0f, 0f, 1f, 1f, SkeletonWeightBrushMode.REPLACE, 0.75f)
        assertEquals(0.75f, replaced.weights[0]["custom_2"])
        val peak = map(listOf(mapOf("custom_2" to 1f), mapOf("custom_1" to 1f), mapOf("custom_1" to 1f)))
        val smoothed = SkeletonManualWeights.paint(spec, peak, "custom_2", 0f, 0f, 1f, 0.5f, SkeletonWeightBrushMode.SMOOTH)
        assertEquals(0.5f, smoothed.weights[0]["custom_2"])
        for (edited in listOf(added, subtracted, replaced, smoothed)) for (row in edited.weights) assertEquals(1f, row.values.sum(), 0.0001f)
        assertEquals(original.weights[1], added.weights[1])
    }

    @Test fun cleanupRepairsUnknownEmptyAndUnnormalizedRowsAndLimitsInfluences() {
        val spec = spec()
        val bad = map(listOf(mapOf("missing" to 9f, "custom_1" to 2f, "custom_2" to 2f), emptyMap(),
            mapOf("custom_1" to 1f, "custom_2" to 0.00001f)))
        val cleaned = SkeletonManualWeights.cleanup(spec, bad, map(), cutoff = 0.001f)
        assertEquals(mapOf("custom_1" to 0.5f, "custom_2" to 0.5f), cleaned.weights[0])
        assertEquals(mapOf("custom_1" to 1f), cleaned.weights[1])
        assertEquals(mapOf("custom_1" to 1f), cleaned.weights[2])
        val rigid = SkeletonManualWeights.cleanup(spec, bad, map(), maxInfluences = 1)
        assertTrue(rigid.weights.all { it.size == 1 && it.values.sum() == 1f })
        val huge = map(List(3) { mapOf("custom_1" to Float.MAX_VALUE, "custom_2" to Float.MAX_VALUE) })
        assertEquals(0.5f, SkeletonManualWeights.cleanup(spec, huge, map()).weights[0]["custom_1"])
    }

    @Test fun copiesSupportInterpolationNearestAndTopologyWithExplicitUnmatchedFallback() {
        val source = map()
        val target = source.copy(positions = listOf(1f, 1f, 10f, 0f, 100f, 100f))
        val mapping = mapOf("custom_1" to "custom_2")
        val interpolated = SkeletonManualWeights.transfer(source, target, mapping, SkeletonWeightTransferMode.INTERPOLATE)
        assertEquals(2, interpolated.matched); assertEquals(1, interpolated.unmatched)
        assertTrue(interpolated.map.weights[2].isEmpty())
        val nearest = SkeletonManualWeights.transfer(source, target, mapping, SkeletonWeightTransferMode.NEAREST, tolerancePx = 2f)
        assertEquals(2, nearest.matched)
        val topology = SkeletonManualWeights.transfer(source, target, mapping, SkeletonWeightTransferMode.TOPOLOGY)
        assertEquals(3, topology.matched)
        assertFailsWith<IllegalArgumentException> { SkeletonManualWeights.transfer(source, target.copy(triangles = listOf(2, 1, 0)), mapping, SkeletonWeightTransferMode.TOPOLOGY) }
        assertEquals(0, SkeletonManualWeights.transfer(source, target, emptyMap(), SkeletonWeightTransferMode.INTERPOLATE).matched)
    }

    @Test fun mirrorTransfersBothCoordinatesAndBoneNames() {
        val source = map()
        val target = source.copy(positions = source.positions.mapIndexed { i, v -> if (i % 2 == 0) -v else v })
        val copied = SkeletonManualWeights.transfer(source, target, mapOf("custom_1" to "custom_2"), SkeletonWeightTransferMode.INTERPOLATE, mirrorAxis = 0f)
        assertEquals(3, copied.matched)
        assertTrue(copied.map.weights.all { it == mapOf("custom_2" to 1f) })
    }

    @Test fun structuralChangesRemapOrRecomputeAuthoredInfluences() {
        val original = spec().withManualWeights("mesh", map(List(3) { mapOf("custom_1" to 0.5f, "custom_2" to 0.5f) }))
        assertTrue(original.withoutBone("custom_2").manualWeights.getValue("mesh").weights.all { it == mapOf("custom_1" to 1f) })
        assertTrue(SkeletonAuthoring.dissolve(original, "custom_2").spec.manualWeights.getValue("mesh").weights.all { it == mapOf("custom_1" to 1f) })
        assertTrue(SkeletonAuthoring.subdivide(original, "custom_1", 2).spec.manualWeights.isEmpty())
        val duplicated = SkeletonAuthoring.duplicate(original, setOf("custom_1"), true, transferBindings = true)
        assertTrue(duplicated.spec.manualWeights.getValue("mesh").weights.all { it.keys.all { id -> id in duplicated.selected } })
        assertTrue(original.withDrawablesBound(setOf("mesh"), null).manualWeights.isEmpty())
    }
}
