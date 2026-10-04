package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceSettingsEditsTest {
    @Test fun customMouthAndGlobalMeshOptionsAreEditableAndNullableValuesReset() {
        val initial = WorkspaceSettingsCodec.encode(PipelineConfig(mouthColor = 0x123456, exportPixelsPerUnit = 1024f))
        val curve = MouthCurve.preset("w")
        val changed = mergeProjectSettings(initial, buildJsonObject {
            put("mouthColor", JsonNull); put("exportPixelsPerUnit", JsonNull)
            put("mouthShape", "custom")
            putJsonArray("mouthCurve") { curve.points.forEach { point -> add(buildJsonObject { put("x", point.x); put("y", point.y) }) } }
            put("meshFillAlgorithm", MeshFillAlgorithm.ADAPTIVE_QUADTREE.name)
            put("meshSuppressBoundaryDiagonals", true)
        })
        val decoded = WorkspaceSettingsCodec.decode(changed)
        assertNull(decoded.mouthColor)
        assertNull(decoded.exportPixelsPerUnit)
        assertEquals(curve, decoded.mouthCurve)
        assertEquals("custom", decoded.mouthShape)
        assertEquals(MeshFillAlgorithm.ADAPTIVE_QUADTREE, decoded.meshFillAlgorithm)
        assertTrue(decoded.meshSuppressBoundaryDiagonals)
    }

    @Test fun partialNestedUpdatesPreserveOtherSettingsAndRejectInvalidCurveOrRange() {
        val initial = WorkspaceSettingsCodec.encode(PipelineConfig(rigTuning = RigTuning(turnDegrees = 17f)))
        val changed = mergeProjectSettings(initial, buildJsonObject {
            putJsonObject("textureUpscale") { put("scale", 2) }
            putJsonObject("meshFillParameters") { putJsonObject("poisson") { put("jitter", 0.1) } }
        })
        val decoded = WorkspaceSettingsCodec.decode(changed)
        assertEquals(17f, decoded.rigTuning.turnDegrees)
        assertEquals(2, decoded.textureUpscale.scale)
        assertEquals(0.1f, decoded.meshFillParameters.poisson.jitter)
        assertEquals(PipelineConfig().meshFillParameters.quadtree, decoded.meshFillParameters.quadtree)
        assertFailsWith<IllegalArgumentException> { mergeProjectSettings(initial, buildJsonObject { put("mouthColor", -1) }) }
        assertFailsWith<IllegalArgumentException> { mergeProjectSettings(initial, buildJsonObject { put("mouthCurve", JsonArray(emptyList())) }) }
        assertFailsWith<IllegalArgumentException> { mergeProjectSettings(initial, buildJsonObject { put("unknown", true) }) }
        assertFailsWith<IllegalArgumentException> { mergeProjectSettings(initial, buildJsonObject { put("meshSpacing", 0) }) }
    }
    @Test fun integerAndFloatSpellingsProduceTheSameDurableSettings() {
        val initial = WorkspaceSettingsCodec.encode(PipelineConfig(headTurnStrength = 2f))
        val integer = mergeProjectSettings(initial, buildJsonObject { put("headStrength", 2) })
        val decimal = mergeProjectSettings(initial, buildJsonObject { put("headStrength", 2.0) })
        assertEquals(initial, integer)
        assertEquals(integer, decimal)
        assertEquals(integer, WorkspaceSettingsCodec.encode(WorkspaceSettingsCodec.decode(integer)))
    }

}
