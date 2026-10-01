package io.github.psd2live.ui.state

import io.github.psd2live.core.DownloadState
import java.nio.file.Path
import kotlin.test.*

class TaskProgressTest {
    @Test fun batchBakeShowsOverallProgressAndRestoresUnderlyingOperation() {
        val state = PSD2LiveState(isGenerating = true, progress = 0.2f, statusText = "Export")
        val bake = PSD2LiveViewModel.SimulationBaking("hair", 0.5f, index = 1, count = 4)
        val task = assertNotNull(taskProgress(state, bake, DownloadState.Idle))
        assertEquals(0.375f, task.fraction)
        assertTrue(task.canCancelBake)
        val resumed = assertNotNull(taskProgress(state, null, DownloadState.Idle))
        assertEquals("Export", resumed.text)
        assertEquals(0.2f, resumed.fraction)
        assertFalse(resumed.canCancelBake)
    }

    @Test fun psdExportHasProgressEvenWithoutOtherBusyFlags() {
        val state = PSD2LiveState(isExportingPsd = true, progress = 0.9f)
        assertEquals(0.9f, taskProgress(state, null, DownloadState.Idle)?.fraction)
        assertNull(taskProgress(state.copy(isExportingPsd = false), null, DownloadState.Idle))
    }

    @Test fun downloadAndInstallationStagesFinishWithoutLeavingAnIndicator() {
        val state = PSD2LiveState()
        assertEquals(0.4f, taskProgress(state, null, DownloadState.Downloading(40, 100, 10, "model", 0.4f))?.fraction)
        assertNull(assertNotNull(taskProgress(state, null, DownloadState.Verifying)).fraction)
        assertNull(assertNotNull(taskProgress(state, null, DownloadState.Extracting)).fraction)
        for (terminal in listOf(DownloadState.Idle, DownloadState.Success(Path.of("model"), Path.of("nunif")), DownloadState.Failed("cancelled"))) {
            assertNull(taskProgress(state, null, terminal))
        }
    }

    @Test fun operationsWithoutMeasuredProgressUseAnIndeterminateIndicator() {
        assertNull(assertNotNull(taskProgress(PSD2LiveState(isAnalyzing = true, isIndeterminateProgress = true), null, DownloadState.Idle)).fraction)
        assertNull(assertNotNull(taskProgress(PSD2LiveState(canvasEditBusy = true), null, DownloadState.Idle)).fraction)
    }
}
