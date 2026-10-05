package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceSourceImporterTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private fun runtime() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
    private val config = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, generatePhysics = false)

    @Test fun psdAndArtworkCreateIndependentRebuildableProjectsAndClearPriorDomainData() = runBlocking {
        val (png, psd) = writeSourceImportFixture(temporary)
        val runtime = runtime()
        val importer = WorkspaceSourceImporter(runtime)
        val polluted = config.copy(layerOverrides = mapOf("absent" to LayerClassificationOverride()),
            deletedLayerIds = setOf("absent"), parentOverrides = mapOf("absent" to "old-parent"), drawOrderOverrides = mapOf("absent" to 500f),
            rigEdits = RigEditOverlay.Empty.copy(authoringJournal = listOf(buildJsonObject { put("op", "invalid-old-command") })),
            hairSimulationFront = true, hairSimulationBack = true)
        val first = importer.importPsd(psd, null, runtime.state.value.state, initialConfig = polluted).capture
        assertEquals(1, runtime.history().selections.size)
        assertTrue(first.document.layerOverrides.isEmpty())
        assertTrue(first.document.parentOverrides.isEmpty())
        assertTrue(first.document.deletedLayerIds.isEmpty())
        assertTrue(first.document.config().drawOrderOverrides.isEmpty())
        assertTrue(first.document.rigEdits.authoringJournal.isEmpty())
        assertFalse(first.document.config().hairSimulationFront)
        assertEquals(first.model.rig.puppet.drawables.map { it.id }, builder.build(first.document).rig.puppet.drawables.map { it.id })
        val withAux = runtime.updateAuxiliary(first.projectId, first.state, buildJsonObject { put("obsolete", true) })
        assertTrue(runtime.saved(withAux.state))
        val second = importer.createArtwork(sourceImportArguments(png), withAux.projectId, withAux.state, initialConfig = polluted).capture
        assertNotEquals(first.projectId, second.projectId)
        assertNotEquals(first.state, second.state)
        assertEquals(1, runtime.history().selections.size)
        assertFalse("obsolete" in second.auxiliary)
        assertEquals(SemanticTag.OBJECTS, second.document.layerOverrides.values.single().tag)
        val oldRaster = PsdReader.read(Files.readAllBytes(psd)).layers.single().raster.rgba
        assertContentEquals(oldRaster, second.document.source.layers.single().raster.rgba)
        assertEquals(second.model.rig.puppet.drawables.map { it.id }, builder.build(second.document).rig.puppet.drawables.map { it.id })
        assertFailsWith<WorkspaceProjectConflict> { importer.importPsd(psd, first.projectId, first.state, discardUnsaved = true) }
        Unit
    }

    @Test fun unsavedSwitchIsRejectedBeforeReadingAndProjectionFailurePreservesTheOldProject() = runBlocking {
        val (png, psd) = writeSourceImportFixture(temporary)
        val runtime = runtime()
        val seed = WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state, initialConfig = config).capture
        val history = runtime.history()
        var reads = 0
        val importer = WorkspaceSourceImporter(runtime, readPsd = { reads++; error("Must not read") },
            readArtwork = { reads++; sourceArtwork(it) })
        assertFailsWith<WorkspaceUnsavedChanges> { importer.importPsd(psd, seed.projectId, seed.state) }
        assertFailsWith<WorkspaceUnsavedChanges> { importer.createArtwork(sourceImportArguments(png), seed.projectId, seed.state) }
        assertEquals(0, reads)
        assertFailsWith<IllegalStateException> {
            importer.createArtwork(sourceImportArguments(png), seed.projectId, seed.state, true, config) { _, _, _ -> error("Rejected projection") }
        }
        assertEquals(seed, runtime.capture())
        assertEquals(history, runtime.history())
    }

    @Test fun sourceReadCannotCommitOverAnEditMadeAfterItsExpectationWasCaptured() = runBlocking {
        val (png, psd) = writeSourceImportFixture(temporary)
        val runtime = runtime()
        val seed = WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state, initialConfig = config).capture
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val importer = WorkspaceSourceImporter(runtime, readPsd = { entered.complete(Unit); release.await(); PsdReader.read(Files.readAllBytes(it)) })
        val pending = async { runCatching { importer.importPsd(psd, seed.projectId, seed.state, true, config) } }
        entered.await()
        val foreign = runtime.updateAuxiliary(seed.projectId, seed.state, buildJsonObject { put("foreign", true) })
        release.complete(Unit)
        assertIs<WorkspaceConflict>(pending.await().exceptionOrNull())
        assertEquals(foreign, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun cancellationBeforeInstallLeavesTheWorkspaceEmptyAndLateCancellationRetainsTheResult() = runBlocking {
        val (png, _) = writeSourceImportFixture(temporary)
        val runtime = runtime()
        val state = runtime.state.value.state
        WorkspaceJobs().use { jobs ->
            val entered = CompletableDeferred<Unit>()
            val blocked = WorkspaceSourceImporter(runtime, readArtwork = { entered.complete(Unit); awaitCancellation() })
            val job = jobs.start("project_create_artwork", null, state) {
                WorkspaceOperationOutput(blocked.createArtwork(sourceImportArguments(png), null, state).mutation("Created").sourceResult())
            }
            entered.await()
            assertEquals(0.1f, jobs.get(job.id).progress)
            jobs.cancel(job.id)
            assertEquals(WorkspaceJobStatus.CANCELLED, jobs.wait(job.id).status)
            assertNull(runtime.state.value.capture)
            assertEquals(state, runtime.state.value.state)
            val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val imported = jobs.start("project_create_artwork", null, state) {
                val result = WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, state, initialConfig = config)
                withContext(NonCancellable) { committed.complete(Unit); release.await() }
                WorkspaceOperationOutput(result.mutation("Created").sourceResult())
            }
            committed.await(); jobs.cancel(imported.id); release.complete(Unit)
            val complete = jobs.wait(imported.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, complete.status)
            assertEquals(runtime.capture().state, complete.result!!.data.getValue("state").jsonPrimitive.content)
            assertEquals(runtime.capture().document.source.layers.single().id.raw, complete.result!!.data.getValue("layers").jsonArray.single().jsonPrimitive.content)
        }
    }
}
