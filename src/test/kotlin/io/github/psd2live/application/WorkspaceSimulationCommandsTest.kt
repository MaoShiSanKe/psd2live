package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceSimulationCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private fun runtime() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
    private fun operation(name: String, id: String = "sway") = WorkspaceDocumentOperation(name, buildJsonObject { put("id", id) })

    @Test fun orderedSimulationCreationBakingClearAndDeleteRebuildAndPersistOneNodePerBatch() = runBlocking<Unit> {
        val runtime = runtime(); val root = simulationFixture(runtime)
        val commands = WorkspaceDocumentCommands(runtime)
        val edits = simulationDrivingEdits(root) + simulationPut(root) + operation("simulation_bake")
        val committed = commands.execute(root.projectId, root.state, "Create and bake", edits, MutationAuthor.AGENT)
        val captured = committed.capture
        val bake = assertNotNull(captured.document.rigEdits.simEdits.single().bake)
        assertTrue(bake.modes.isNotEmpty())
        assertTrue(captured.model.rig.puppet.parameters.any { it.id.raw == bake.parameters.first() })
        assertTrue(captured.document.settings.getValue("generatePhysics").jsonPrimitive.boolean)
        assertEquals(2, runtime.history().selections.size)
        val replayed = builder.build(captured.document)
        assertEquals(captured.model.rig.puppet.parameters, replayed.rig.puppet.parameters)
        val expectedGrid = assertNotNull(captured.model.rig.puppet.drawables.first().geometryGrid)
        val replayedGrid = assertNotNull(replayed.rig.puppet.drawables.first().geometryGrid)
        assertEquals(expectedGrid.axes.map { it.parameterId }, replayedGrid.axes.map { it.parameterId })
        expectedGrid.axes.zip(replayedGrid.axes).forEach { (expected, actual) -> assertContentEquals(expected.keys, actual.keys) }
        assertEquals(expectedGrid.cellsByLinearIndex.keys, replayedGrid.cellsByLinearIndex.keys)
        expectedGrid.cellsByLinearIndex.forEach { (index, expected) ->
            val actual = replayedGrid.cellsByLinearIndex.getValue(index)
            assertContentEquals(expected.coordinate, actual.coordinate)
            assertContentEquals(expected.form.positionDeltas, actual.form.positionDeltas)
        }
        val store = WorkspaceStore(temporary)
        store.persistHistory(captured.projectId, runtime.history())
        val reopened = assertNotNull(WorkspaceStore(temporary).loadHistory(captured.projectId))
        assertEquals(runtime.history().selections.map { it.node }, reopened.state().selections.map { it.node })
        assertEquals(bake, reopened.head().snapshot.rigEdits.simEdits.single().bake)
        assertEquals(captured.revision, WorkspaceRevisions.of(reopened.head().snapshot))
        val noop = commands.execute(captured.projectId, captured.state, "Same simulation", listOf(simulationPut(captured)), MutationAuthor.USER)
        assertFalse(noop.applied)
        assertEquals(2, runtime.history().selections.size)
        val removed = commands.execute(captured.projectId, captured.state, "Clear and delete", listOf(
            operation("simulation_clear_bake"), operation("simulation_delete")), MutationAuthor.USER).capture
        assertTrue(removed.document.rigEdits.simEdits.isEmpty())
        assertTrue(removed.model.rig.puppet.parameters.none { it.id.raw in bake.parameters })
        assertEquals(3, runtime.history().selections.size)
        val restored = runtime.checkout(removed.projectId, removed.state, captured.historyHead)
        assertEquals(bake, restored.document.rigEdits.simEdits.single().bake)
    }

    @Test fun invalidLaterSimulationDoesNotPublishEarlierWeightsParametersOrHistory() = runBlocking<Unit> {
        val runtime = runtime(); val root = simulationFixture(runtime)
        val history = runtime.history()
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Invalid batch",
                simulationDrivingEdits(root) + simulationPut(root) + operation("simulation_bake", "missing"), MutationAuthor.AGENT)
        }
        assertEquals(4, failure.index)
        assertEquals("simulation_bake", failure.editOperation)
        assertEquals(root, runtime.capture())
        assertEquals(history, runtime.history())
    }

    @Test fun cancellationDuringActualAutoBakeRollsBackTheWholeCandidateDespiteTheCoreFailureReport() = runBlocking<Unit> {
        val runtime = runtime(); val root = simulationFixture(runtime)
        val history = runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val observed = object : WorkspaceSimulationWork {
            override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)) { "Bake test was not released" }
            }, { false })
        }
        val pending = async(Dispatchers.Default) { WorkspaceDocumentCommands(runtime, observed).execute(root.projectId, root.state, "Cancelled auto-bake",
            simulationDrivingEdits(root) + simulationPut(root, autoBake = true), MutationAuthor.AGENT) }
        try {
            withTimeout(5000) { entered.await() }
            pending.cancel(); release.countDown()
            assertFailsWith<CancellationException> { pending.await() }
            pending.join()
            assertEquals(root, runtime.capture())
            assertEquals(history, runtime.history())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun concurrentDurableEditDuringBakingRejectsTheOriginalExpectationAndRetainsTheForeignState() = runBlocking<Unit> {
        val runtime = runtime(); val root = simulationFixture(runtime)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val observed = object : WorkspaceSimulationWork {
            override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({
                entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            }, { false })
        }
        supervisorScope {
            val pending = async(Dispatchers.Default) { WorkspaceDocumentCommands(runtime, observed).execute(root.projectId, root.state, "Concurrent bake",
                simulationDrivingEdits(root) + simulationPut(root) + operation("simulation_bake"), MutationAuthor.AGENT) }
            try {
                withTimeout(5000) { entered.await() }
                val foreign = runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject { put("foreign", true) })
                release.countDown()
                val conflict = assertFailsWith<WorkspaceConflict> { pending.await() }
                assertEquals(root.state, conflict.expectedState)
                assertEquals(foreign.state, conflict.actualState)
                assertEquals(foreign, runtime.capture())
                assertEquals(1, runtime.history().selections.size)
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun presetsAndClassicSwaySwitchUseTheSameDocumentCandidateAndReturnRealBakeDiagnostics() = runBlocking<Unit> {
        val runtime = runtime(); val root = simulationFixture(runtime, hair = true)
        val commands = WorkspaceDocumentCommands(runtime)
        val preset = WorkspaceDocumentOperation("model_apply_preset", buildJsonObject { put("preset", "back_hair") })
        val after = commands.execute(root.projectId, root.state, "Preset", listOf(preset), MutationAuthor.USER).capture
        assertTrue(after.document.settings.getValue("hairSimulationBack").jsonPrimitive.boolean)
        assertEquals(ModelPresets.BACK_HAIR_SIM, after.document.rigEdits.simEdits.single().id)
        assertTrue(after.model.rig.puppet.vertexGroups.any { it.name == "preset_pin" })
        val again = commands.execute(after.projectId, after.state, "Same preset", listOf(preset), MutationAuthor.AGENT)
        assertFalse(again.applied)
        val classic = commands.execute(after.projectId, after.state, "Classic without sway", listOf(WorkspaceDocumentOperation("model_apply_preset",
            buildJsonObject { put("preset", "classic_back_hair"); put("sway", false) })), MutationAuthor.AGENT).capture
        assertFalse(classic.document.settings.getValue("hairSimulationBack").jsonPrimitive.boolean)
        assertFalse(classic.document.settings.getValue("physicsBackHair").jsonPrimitive.boolean)
        assertTrue(classic.document.rigEdits.simEdits.isEmpty())
        assertEquals(3, runtime.history().selections.size)
        assertEquals(classic.model.rig.puppet.parameters, builder.build(classic.document).rig.puppet.parameters)
    }
}
