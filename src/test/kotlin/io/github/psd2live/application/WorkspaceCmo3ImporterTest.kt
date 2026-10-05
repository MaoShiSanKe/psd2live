package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCmo3ImporterTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private fun runtime() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })

    @Test fun neutralNewAndReplacementImportsPreserveObjectsAndReplayWithoutGeneratedPresets() = runBlocking {
        val runtime = runtime()
        val importer = WorkspaceCmo3Importer(runtime)
        val first = writeCmo3Fixture(temporary.resolve("first.cmo3"), "old", "shared")
        val incoming = writeCmo3Fixture(temporary.resolve("incoming.cmo3"), "shared", "added", color = 0xff4070dd.toInt())
        val root = importer.import(first, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER,
            initialConfig = PipelineConfig(atlasSize = 256)).capture
        assertEquals(setOf("old", "shared"), root.model.rig.puppet.drawables.map { it.id.raw }.toSet())
        assertEquals(listOf("ParamCustom"), root.model.rig.puppet.parameters.map { it.id.raw })
        assertTrue(root.model.rig.puppet.deformers.isEmpty())
        assertFalse(root.document.rigEdits.skeleton!!.enabled)
        assertFalse(root.document.settings.getValue("motionBasic").jsonPrimitive.boolean)
        val poses = runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject {
            put("annotation", "keep")
            putJsonObject("posesByWorkspace") { put("editor", PreviewSessions.encode(WorkspacePose(
                mapOf(org.umamo.runtime.model.ParameterId("ParamCustom") to 1f), setOf(org.umamo.runtime.model.ParameterId("ParamCustom"))))) }
        })
        val replaced = importer.import(incoming, Cmo3ImportMode.REPLACE, poses.projectId, poses.state, MutationAuthor.AGENT).capture
        assertEquals(root.projectId, replaced.projectId)
        assertEquals(setOf("old", "shared", "added"), replaced.model.rig.puppet.drawables.map { it.id.raw }.toSet())
        assertEquals(2, runtime.history().selections.size)
        assertEquals("agent", runtime.history().selections.last().node.actor)
        assertEquals(JsonPrimitive("keep"), replaced.auxiliary["annotation"])
        val pose = replaced.auxiliary.getValue("posesByWorkspace").jsonObject.getValue("editor").jsonObject
        assertEquals(0f, pose.getValue("values").jsonObject.getValue("ParamCustom").jsonPrimitive.float)
        assertTrue(pose.getValue("locked").jsonArray.isEmpty())
        val rebuilt = builder.build(replaced.document)
        assertEquals(replaced.model.rig.puppet.drawables.map { it.id }, rebuilt.rig.puppet.drawables.map { it.id })
        assertTrue(PhysicsCatalog.groups(rebuilt.analysis, rebuilt.config, setOf("ParamCustom")).isEmpty())
        runtime.checkout(root.projectId, replaced.state, root.historyHead)
        assertEquals(setOf("old", "shared"), runtime.capture().model.rig.puppet.drawables.map { it.id.raw }.toSet())
    }

    @Test fun replacementEndsEveryStoredCanvasSoloInTheSameCommit() = runBlocking {
        val runtime = runtime()
        val importer = WorkspaceCmo3Importer(runtime)
        val first = writeCmo3Fixture(temporary.resolve("first.cmo3"), "old", "shared")
        val incoming = writeCmo3Fixture(temporary.resolve("incoming.cmo3"), "shared", "added")
        val root = importer.import(first, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER).capture
        val soloed = CanvasAddress("main", "canvas", CanvasViewMode.EDIT)
        val plain = CanvasAddress("main", "canvas", CanvasViewMode.PREVIEW)
        val records = mapOf(soloed to CanvasVisibility(mapOf("old" to false, "shared" to true), mapOf("warp" to false), "shared", mapOf("old" to true)),
            plain to CanvasVisibility(mapOf("old" to false)))
        val stored = runtime.updateAuxiliary(root.projectId, root.state, CanvasVisibilityCodec.withRecords(root.auxiliary, records))
        val replaced = importer.import(incoming, Cmo3ImportMode.REPLACE, stored.projectId, stored.state, MutationAuthor.USER).capture
        assertEquals(mapOf(soloed to CanvasVisibility(mapOf("old" to false, "shared" to true), mapOf("warp" to false)),
            plain to records.getValue(plain)), CanvasVisibilityCodec.decode(replaced.auxiliary))
        assertEquals(2, runtime.history().selections.size)
        // Without a solo the replacement leaves the stored auxiliary exactly as it was.
        val again = importer.import(first, Cmo3ImportMode.REPLACE, replaced.projectId, replaced.state, MutationAuthor.USER).capture
        assertEquals(replaced.auxiliary, again.auxiliary)
    }

    @Test fun newImportRequiresExplicitDiscardAndRejectedProjectionLeavesTheOldProjectIntact() = runBlocking {
        val runtime = runtime()
        val importer = WorkspaceCmo3Importer(runtime)
        val path = writeCmo3Fixture(temporary.resolve("model.cmo3"), "mesh")
        val original = importer.import(path, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER).capture
        val history = runtime.history()
        assertFailsWith<IllegalStateException> {
            importer.import(path, Cmo3ImportMode.NEW, original.projectId, original.state, MutationAuthor.AGENT)
        }
        assertFailsWith<IllegalStateException> {
            importer.import(path, Cmo3ImportMode.NEW, original.projectId, original.state, MutationAuthor.USER,
                discardUnsaved = true) { _, _, _, _ -> error("Projection rejected") }
        }
        assertEquals(original, runtime.capture())
        assertEquals(history, runtime.history())
        val installed = importer.import(path, Cmo3ImportMode.NEW, original.projectId, original.state, MutationAuthor.USER,
            discardUnsaved = true).capture
        assertNotEquals(original.projectId, installed.projectId)
        assertNotEquals(original.state, installed.state)
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun fileReadStartsAfterCapturingTheExpectationAndForeignEditsRejectInstallation() = runBlocking {
        val runtime = runtime()
        val path = writeCmo3Fixture(temporary.resolve("model.cmo3"), "mesh")
        val root = WorkspaceCmo3Importer(runtime).import(path, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER).capture
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = WorkspaceCmo3Importer(runtime, read = { entered.complete(Unit); release.await(); Files.readAllBytes(it) })
        val candidate = async { runCatching { slow.import(path, Cmo3ImportMode.REPLACE, root.projectId, root.state, MutationAuthor.AGENT) } }
        entered.await()
        val foreign = runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject { put("foreign", true) })
        release.complete(Unit)
        assertIs<WorkspaceConflict>(candidate.await().exceptionOrNull())
        assertEquals(foreign, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun cancellationBeforeImportCommitPreservesTheEmptyWorkspaceAndReportsProgress() = runBlocking {
        val runtime = runtime()
        val state = runtime.state.value.state
        val path = writeCmo3Fixture(temporary.resolve("model.cmo3"), "mesh")
        val entered = CompletableDeferred<Unit>()
        val importer = WorkspaceCmo3Importer(runtime, read = { entered.complete(Unit); awaitCancellation() })
        WorkspaceJobs().use { jobs ->
            val job = jobs.start("project_import_cmo3", null, state) {
                WorkspaceOperationOutput(importer.import(path, Cmo3ImportMode.NEW, null, state, MutationAuthor.AGENT).mutation("Imported").lifecycleResult())
            }
            entered.await()
            assertEquals(0.1f, jobs.get(job.id).progress)
            jobs.cancel(job.id)
            assertEquals(WorkspaceJobStatus.CANCELLED, jobs.wait(job.id, 5000).status)
            assertEquals(state, runtime.state.value.state)
            assertNull(runtime.state.value.capture)
        }
    }

    @Test fun lateCancellationRetainsACommittedImportAndItsNewProjectResult() = runBlocking {
        val runtime = runtime()
        val state = runtime.state.value.state
        val path = writeCmo3Fixture(temporary.resolve("model.cmo3"), "mesh")
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        WorkspaceJobs().use { jobs ->
            val job = jobs.start("project_import_cmo3", null, state) {
                val imported = WorkspaceCmo3Importer(runtime).import(path, Cmo3ImportMode.NEW, null, state, MutationAuthor.AGENT)
                withContext(NonCancellable) { committed.complete(Unit); release.await() }
                WorkspaceOperationOutput(imported.mutation("Imported").lifecycleResult())
            }
            committed.await()
            jobs.cancel(job.id)
            release.complete(Unit)
            val done = jobs.wait(job.id, 5000)
            assertEquals(WorkspaceJobStatus.COMPLETED, done.status)
            assertEquals(runtime.capture().state, done.result!!.data.getValue("state").jsonPrimitive.content)
            assertEquals(runtime.capture().projectId, done.result!!.data.getValue("project_id").jsonPrimitive.content)
        }
    }
}
