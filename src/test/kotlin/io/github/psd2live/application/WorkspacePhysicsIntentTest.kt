package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspacePhysicsIntentTest {
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Physics parameters", listOf("Drive", "Response").map { id ->
            WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                put("parameter_id", id); put("name", id); put("min", -30); put("max", 30)
            })
        }, MutationAuthor.USER)
        return runtime
    }
    private fun setting(id: String = "spring", normalization: PhysicsNormalization = PhysicsNormalization()) = RigPhysicsEdit(id, "Spring",
        listOf(PhysicsInput("Drive", type = PhysicsSourceType.X)), listOf(PhysicsOutput("Response", scale = 0.5f)),
        listOf(PhysicsSegment(8f)), normalization)
    private suspend fun apply(runtime: WorkspaceRuntime<RigPreviewModel>, intent: WorkspacePhysicsIntent): WorkspacePhysicsCommit {
        val capture = runtime.capture()
        return WorkspacePhysicsCommands(runtime).executeIntent(capture.projectId, capture.state, intent, "Physics panel", MutationAuthor.USER)
    }

    @Test fun typedActionsResolveCapturedIdsAndNamesAndReplayTheSamePublicCandidates() = runBlocking<Unit> {
        val runtime = fixture()
        apply(runtime, WorkspacePhysicsIntent.Put(setting()))
        val origin = runtime.capture()
        val duplicate = apply(runtime, WorkspacePhysicsIntent.Create("Spring", "spring"))
        val id = duplicate.report.getValue("created").jsonPrimitive.content
        val copy = runtime.capture().document.rigEdits.physicsEdits.first { it.id == id }
        assertEquals("Spring 2", copy.name)
        assertEquals(setting().inputs, copy.inputs)
        assertTrue(copy.outputs.isEmpty())
        assertEquals(listOf("physics:$id"), duplicate.mutation.affectedObjectIds)
        assertFalse(apply(runtime, WorkspacePhysicsIntent.Enabled(id, false)).commit.capture.document.rigEdits.disabledPhysicsIds.isEmpty())
        apply(runtime, WorkspacePhysicsIntent.Move(id, -1))
        assertEquals(id, runtime.capture().document.rigEdits.physicsOrder.first())
        apply(runtime, WorkspacePhysicsIntent.Fps(30))
        val preset = PhysicsPresets.Preset(PhysicsPresets.Kind.INPUT, "Inputs", listOf(PhysicsInput("Drive"), PhysicsInput("Missing")),
            PhysicsNormalization(angleMin = -20f, angleMax = 20f))
        val before = runtime.capture()
        val operation = WorkspacePhysicsIntents.operation(before.document, before.model, WorkspacePhysicsIntent.Preset("spring", preset))
        val expected = WorkspacePhysicsEdits.apply(operation, before.document, before.model)
        apply(runtime, WorkspacePhysicsIntent.Preset("spring", preset))
        assertEquals(expected, runtime.capture().document)
        assertEquals(listOf("Drive"), runtime.capture().document.rigEdits.physicsEdits.first { it.id == "spring" }.inputs.map { it.parameter })
        apply(runtime, WorkspacePhysicsIntent.FitObserved("spring", mapOf(0 to 0.5f)))
        assertEquals(1f, runtime.capture().document.rigEdits.physicsEdits.first { it.id == "spring" }.outputs.single().scale)
        assertFalse(apply(runtime, WorkspacePhysicsIntent.FitObserved("spring", mapOf(0 to 1f))).mutation.applied)
        apply(runtime, WorkspacePhysicsIntent.Delete(id))
        val final = runtime.capture()
        assertEquals(WorkspaceDocumentEdits.physicsCatalog(final.document, final.model),
            WorkspaceDocumentEdits.physicsCatalog(final.document, builder.build(final.document)))
        runtime.checkout(final.projectId, final.state, origin.historyHead)
        assertEquals(origin.document, runtime.capture().document)
        val current = runtime.capture()
        runtime.checkout(current.projectId, current.state, final.historyHead)
        assertEquals(final.document, runtime.capture().document)
    }

    @Test fun fullTypedReplacementResetsDefaultNormalizationInsteadOfApplyingAnOmittedPatch() = runBlocking<Unit> {
        val runtime = fixture()
        apply(runtime, WorkspacePhysicsIntent.Put(setting(normalization = PhysicsNormalization(angleMin = -50f, angleMax = 50f))))
        apply(runtime, WorkspacePhysicsIntent.Put(setting()))
        assertEquals(PhysicsNormalization(), runtime.capture().document.rigEdits.physicsEdits.single().normalization)
        assertFalse(apply(runtime, WorkspacePhysicsIntent.Put(setting())).mutation.applied)
    }

    @Test fun publicPresetApplicationFiltersInputsAndClampsOutputTipsUsingTheSameBatchCandidate() = runBlocking<Unit> {
        val runtime = fixture()
        val original = setting().copy(segments = listOf(PhysicsSegment(6f), PhysicsSegment(8f)),
            outputs = listOf(PhysicsOutput("Response", vertex = 2)))
        apply(runtime, WorkspacePhysicsIntent.Put(original))
        val before = runtime.capture()
        val inputPreset = PhysicsPresets.Preset(PhysicsPresets.Kind.INPUT, "Inputs", listOf(
            PhysicsInput("Drive"), PhysicsInput("Missing"), PhysicsInput("Response")))
        val pendulum = PhysicsPresets.Preset(PhysicsPresets.Kind.PENDULUM, "One segment", segments = listOf(PhysicsSegment(4f)))
        val edits = listOf(inputPreset, pendulum).map { preset -> WorkspaceDocumentOperation(WorkspacePhysicsEdits.PRESET,
            buildJsonObject { put("id", "spring"); put("preset", preset.toJson()) }) }
        val commands = WorkspaceDocumentCommands(runtime)
        val port = object : WorkspaceBackendStub() {
            override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
            override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
                val initial = runtime.capture()
                val result = commands.execute(initial.projectId, state, summary, edits, author)
                return WorkspaceDocumentCommands.mutationResult(initial, result, summary, edits)
            }
        }
        val requests = WorkspaceRequestExecutor(port)
        val registry = WorkspaceOperationRegistry(requests)
        registerPhysicsPresetApplyOperation(registry, port)
        val request = buildJsonObject {
            put("project_id", before.projectId); put("state", before.state); put("request_id", "preset")
            put("id", "spring"); put("preset", inputPreset.toJson())
        }
        try {
            val result = registry.invoke(WorkspacePhysicsEdits.PRESET, request, WorkspaceOperationContext(MutationAuthor.AGENT))
            assertEquals(result, registry.invoke(WorkspacePhysicsEdits.PRESET, request, WorkspaceOperationContext(MutationAuthor.AGENT)))
            assertEquals(listOf("Drive"), runtime.capture().document.rigEdits.physicsEdits.single().inputs.map { it.parameter })
            assertFailsWith<WorkspaceValidationException> { registry.invoke(WorkspacePhysicsEdits.PRESET,
                JsonObject(request + ("preset" to JsonObject(inputPreset.toJson() + ("unused" to JsonPrimitive(true))))), WorkspaceOperationContext(MutationAuthor.AGENT)) }
            val current = runtime.capture()
            commands.execute(current.projectId, current.state, "Both presets", edits, MutationAuthor.USER)
            val setting = runtime.capture().document.rigEdits.physicsEdits.single()
            assertEquals(1, setting.outputs.single().vertex); assertEquals(4f, setting.segments.single().length)
            assertEquals(listOf("Drive"), setting.inputs.map { it.parameter })
        } finally { requests.close() }
    }

    @Test fun staleCancelledAndRejectedPanelActionsPublishNoCandidateOrHistoryPrefix() = runBlocking<Unit> {
        val runtime = fixture()
        val commands = WorkspacePhysicsCommands(runtime)
        val before = runtime.capture()
        runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("external", true) })
        val current = runtime.capture(); val history = runtime.history()
        assertFailsWith<WorkspaceConflict> {
            commands.executeIntent(before.projectId, before.state, WorkspacePhysicsIntent.Create("Old"), "Old panel", MutationAuthor.USER)
        }
        assertFailsWith<IllegalStateException> {
            commands.executeIntent(current.projectId, current.state, WorkspacePhysicsIntent.Create("Rejected"), "Rejected panel", MutationAuthor.USER) { _, _, _ ->
                error("Projection changed")
            }
        }
        val job = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { commands.executeIntent(current.projectId, current.state, WorkspacePhysicsIntent.Create("Cancelled"), "Cancelled panel", MutationAuthor.USER) }
        }
        assertEquals(current, runtime.capture()); assertEquals(history, runtime.history())
        apply(runtime, WorkspacePhysicsIntent.Put(setting()))
        val withGroup = runtime.capture(); val groupHistory = runtime.history()
        for (peaks in listOf(mapOf(-1 to 0.5f), mapOf(0 to Float.NaN), mapOf(0 to -1f), emptyMap())) {
            assertFailsWith<IllegalArgumentException> { apply(runtime, WorkspacePhysicsIntent.FitObserved("spring", peaks)) }
        }
        assertEquals(withGroup, runtime.capture()); assertEquals(groupHistory, runtime.history())
    }
}
