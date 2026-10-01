package io.github.psd2live.core

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PreviewParameterPoseTest {
    private val head = StandardParameters.ANGLE_Y
    private val roll = StandardParameters.ANGLE_Z
    private val mouth = StandardParameters.MOUTH_OPEN
    private val definitions = listOf(
        Parameter(head, "Head", -10f, 10f, 0f),
        Parameter(roll, "Roll", -30f, 30f, 0f),
        Parameter(mouth, "Mouth", 0f, 1f, 0f),
    )

    @Test fun trackingReplacesManualLookAndRespectsEditedModelRanges() {
        val manual = mapOf(head to 10f, roll to 12f, mouth to 0.5f)
        assertEquals(mapOf(head to 10f, roll to 12f, mouth to 0.5f),
            pointerPreviewPose(manual, 0f, 1f, definitions, tracking = true))
        assertEquals(-10f, pointerPreviewPose(manual, 0f, -1f, definitions, tracking = true)[head])
        assertEquals(0f, pointerPreviewPose(manual, 0f, 0f, definitions, tracking = true)[head])
        assertSame(manual, pointerPreviewPose(manual, 0f, 0f, definitions, tracking = false))
        // The preview never writes the inspector pose back into the document.
        assertEquals(10f, manual[head])
    }

    @Test fun trackedPoseDoesNotInheritManualOffsetEvenWhenItIsInsideTheRange() {
        val fullRange = definitions.map { if (it.id == head) it.copy(min = -30f, max = 30f) else it }
        assertEquals(15f, pointerPreviewPose(mapOf(head to 25f), 0f, 0.5f, fullRange, true)[head])
    }

    @Test fun lockedLookStaysAuthoritativeWhileFinalPoseIsBounded() {
        assertEquals(8f, pointerPreviewPose(mapOf(head to 8f), 0f, -1f, definitions, true, setOf(head))[head])
        assertEquals(10f, pointerPreviewPose(mapOf(head to 80f), 0f, -1f, definitions, true, setOf(head))[head])
    }

    @Test fun finalCompositionBoundsMotionPhysicsAndNonFiniteValues() {
        val custom = Parameter(ParameterId("ParamSim"), "Simulation", -0.5f, 0.5f, 0f)
        val pose = boundedPreviewPose(mapOf(head to 60f, roll to -90f, mouth to Float.NaN, custom.id to 2f), definitions + custom)
        assertEquals(mapOf(head to 10f, roll to -30f, mouth to 0f, custom.id to 0.5f), pose)
        assertSame(pose, boundedPreviewPose(pose, definitions + custom))
    }
}
