package io.github.psd2live.application

import io.github.psd2live.ui.state.WorkspaceStateCodec

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceSettingsCodecTest {
    @Test fun meshUnitsRoundTripAndLegacyArchivesKeepSourcePixelUnits() {
        for (units in MeshUnits.entries) {
            val config = PipelineConfig(meshUnits = units)
            assertEquals(config, WorkspaceSettingsCodec.decode(WorkspaceSettingsCodec.encode(config)))
        }
        assertEquals(MeshUnits.PIXELS, WorkspaceSettingsCodec.decode(buildJsonObject { put("meshSpacing", 40) }).meshUnits)
        assertEquals(MeshUnits.DOCUMENT, WorkspaceSettingsCodec.decode(buildJsonObject {}).meshUnits)
        val document = WorkspaceSettingsCodec.encode(PipelineConfig(meshUnits = MeshUnits.DOCUMENT))
        assertEquals(MeshUnits.PIXELS, WorkspaceSettingsCodec.decode(
            mergeProjectSettings(document, buildJsonObject { put("meshUnits", "PIXELS") })).meshUnits)
        assertFailsWith<IllegalArgumentException> {
            mergeProjectSettings(document, buildJsonObject { put("meshUnits", "invalid") })
        }
    }
    @Test fun documentConfigPreservesDesktopGenerationPolicyAndCustomSettings() {
        val cases = listOf(
            PSD2LiveState(),
            PSD2LiveState(meshOnly = true, generatePhysics = true),
            PSD2LiveState(atlasSize = 512, meshFillAlgorithm = MeshFillAlgorithm.ADAPTIVE_QUADTREE,
                meshSuppressBoundaryDiagonals = true, mouthColor = 0x456789,
                drawOrderOverrides = mapOf("mesh" to 123f),
                rigTuning = RigTuning(turnDegrees = 17f), motionBasic = false, motionSkeleton = false),
        )
        for (state in cases) {
            val document = WorkspaceDocument(WorkspaceSourceArt(32, 32, emptyList(), emptyList()), emptyMap(),
                emptySet(), emptyMap(), emptyMap(), state.rigEdits, WorkspaceStateCodec.settings(state))
            assertEquals(state.buildConfig(), document.config(), "Domain settings must reproduce the desktop configuration")
        }
    }

    @Test fun existingLegacyAndNullableSettingsDecodeWithoutUiState() {
        val codec = WorkspaceSettingsCodec
        val custom = PipelineConfig(mouthColor = 0x123456, exportPixelsPerUnit = 512f,
            drawOrderOverrides = mapOf("mesh" to 234f),
            headTurnStrength = 2f, meshFillParameters = MeshFillParameters(poisson = PoissonFillParameters(jitter = 0.1f)))
        assertEquals(custom, codec.decode(codec.encode(custom)))
        val reset = codec.decode(buildJsonObject { put("mouthColor", JsonNull); put("exportPixelsPerUnit", JsonNull) }, custom)
        assertNull(reset.mouthColor)
        assertNull(reset.exportPixelsPerUnit)
        val legacy = codec.decode(buildJsonObject {
            putJsonObject("bodyTuning") { put("turnDegrees", 20) }
            put("meshOuterMargin", 3); put("meshInnerMargin", 4)
        })
        assertEquals(20f, legacy.rigTuning.turnDegrees)
        assertEquals(7f, legacy.meshEdgeWidth)
    }
}
