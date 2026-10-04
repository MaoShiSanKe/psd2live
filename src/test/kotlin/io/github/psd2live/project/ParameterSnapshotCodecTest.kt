package io.github.psd2live.project

import io.github.psd2live.ui.state.WorkspaceStateCodec

import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.project.ParameterSnapshot
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

class ParameterSnapshotCodecTest {
    @Test fun parameterSnapshotsSurviveSaveAndOpen() {
        val snapshots = listOf(
            ParameterSnapshot("a", 1, "Smile", mapOf(ParameterId("ParamMouthForm") to 1f, ParameterId("ParamAngleX") to -12.5f)),
            ParameterSnapshot("b", 3, "", emptyMap()),
        )
        val restored = WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(PSD2LiveState(parameterSnapshots = snapshots)))
        assertEquals(snapshots, restored.parameterSnapshots)
    }

    @Test fun aSettingsOnlyDecodeKeepsTheLiveSnapshots() {
        val snapshots = listOf(ParameterSnapshot("a", 1, "Smile", mapOf(ParameterId("ParamMouthForm") to 1f)))
        val live = PSD2LiveState(parameterSnapshots = snapshots)
        assertEquals(snapshots, WorkspaceStateCodec.decode(WorkspaceStateCodec.settings(live), live).parameterSnapshots)
    }
}
