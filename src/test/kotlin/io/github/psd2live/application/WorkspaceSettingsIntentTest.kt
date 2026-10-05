package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.*

/** Settings links, raw/effective policy and authored-pose releases, through real candidates and CAS. */
class WorkspaceSettingsIntentTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    private suspend fun runtime(config: PipelineConfig = PipelineConfig(atlasSize = 256, exportMoc3 = false)): WorkspaceRuntime<RigPreviewModel> {
        val (png, _) = writeSourceImportFixture(temporary)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state, initialConfig = config)
        return runtime
    }

    private fun settings(vararg fields: Pair<String, Any>) = WorkspaceDocumentOperation("settings_update", buildJsonObject {
        putJsonObject("changes") { fields.forEach { (key, value) -> if (value is Boolean) put(key, value) else put(key, value as Number) } }
    })

    /** Two workspaces with posed parameters; each locks a different one of [locked]. */
    private suspend fun posed(runtime: WorkspaceRuntime<RigPreviewModel>, values: Map<ParameterId, Float>,
                              lockedA: Set<ParameterId>, lockedB: Set<ParameterId>) {
        val before = runtime.capture()
        val parameters = before.model.rig.puppet.parameters
        values.keys.forEach { id -> assertNotNull(parameters.firstOrNull { it.id == id }, "fixture lacks $id") }
        WorkspacePreviewCommands(runtime).authored(before.projectId, before.state, mapOf(
            "a" to WorkspacePose(values, lockedA), "b" to WorkspacePose(values, lockedB)))
    }

    private fun pose(runtime: WorkspaceRuntime<RigPreviewModel>, workspace: String) = runtime.capture().let {
        PreviewSessions.read(it.model.rig.puppet.parameters, it.auxiliary, workspace)
    }
    private fun default(runtime: WorkspaceRuntime<RigPreviewModel>, id: ParameterId) =
        runtime.capture().model.rig.puppet.parameters.single { it.id == id }.default

    @Test fun importsKeepRawSettingsWhileGenerationReadsEffectiveOnes() = runBlocking {
        // Generation gates physics and motions off under mesh-only; the document must still hold the choices.
        val runtime = runtime(PipelineConfig(atlasSize = 256, exportMoc3 = false, meshOnly = true, generateDeformers = true,
            generatePhysics = true, exportMotions = true))
        val document = runtime.capture().document
        assertEquals(true, document.settings.getValue("generatePhysics").jsonPrimitive.boolean)
        assertEquals(true, document.settings.getValue("generateDeformers").jsonPrimitive.boolean)
        assertEquals(true, document.settings.getValue("exportMotions").jsonPrimitive.boolean)
        val effective = document.config()
        assertFalse(effective.generatePhysics); assertFalse(effective.generateDeformers); assertFalse(effective.exportMotions)
        assertFalse(runtime.capture().model.config.generatePhysics)
        // Turning mesh-only back off restores the raw choices rather than the values generation derived.
        val before = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Leave mesh-only",
            listOf(settings("meshOnly" to false)), MutationAuthor.AGENT)
        val restored = runtime.capture().document.config()
        assertTrue(restored.generatePhysics); assertTrue(restored.generateDeformers)
    }

    @Test fun explicitPublicFlagsWinOverConfigReadsWithV1Compatibility() {
        val clip = MotionClip("custom", "Custom")
        fun effective(config: PipelineConfig) = WorkspaceSettingsPolicy.effective(config)
        assertFalse(effective(PipelineConfig(meshOnly = false, generateDeformers = false)).generateDeformers)
        assertTrue(effective(PipelineConfig(meshOnly = false, generateDeformers = true)).generateDeformers)
        assertFalse(effective(PipelineConfig(meshOnly = false, exportMotions = false, motionBasic = true, motionIdle = true)).exportMotions)
        assertTrue(effective(PipelineConfig(meshOnly = false, exportMotions = true, motionBasic = true, motionIdle = true)).exportMotions)
        // v1 desktops wrote exportMotions=false whenever every generated motion was off, and custom clips still exported.
        val legacy = PipelineConfig(exportMotions = false, motionIdle = false, motionBlink = false, motionNod = false,
            motionShake = false, motionSkeleton = false, rigEdits = RigEditOverlay.Empty.copy(motionClips = listOf(clip)))
        assertTrue(effective(legacy).exportMotions)
        val config = PipelineConfig(meshOnly = false, generateDeformers = false, generatePhysics = false, exportMotions = false,
            motionBasic = true, motionIdle = true)
        assertEquals(effective(config), effective(effective(config)))
    }

    @Test fun meshOnlyLinksDeformersUnlessExplicitAndReleasesEveryWorkspacePoseExceptItsLocks() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_X; val eye = StandardParameters.EYE_L_OPEN
        posed(runtime, mapOf(angle to 20f, eye to 0.2f), lockedA = setOf(angle), lockedB = setOf(eye))
        val before = runtime.capture(); val nodes = runtime.history().selections.size
        val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Mesh only",
            listOf(settings("meshOnly" to true)), MutationAuthor.USER)
        assertTrue(result.applied)
        assertEquals(nodes + 1, runtime.history().selections.size)
        assertFalse(runtime.capture().document.settings.getValue("generateDeformers").jsonPrimitive.boolean)
        assertEquals(20f, pose(runtime, "a").values.getValue(angle)); assertEquals(default(runtime, eye), pose(runtime, "a").values.getValue(eye))
        assertEquals(default(runtime, angle), pose(runtime, "b").values.getValue(angle)); assertEquals(0.2f, pose(runtime, "b").values.getValue(eye))
        assertEquals(setOf(angle), pose(runtime, "a").locked); assertEquals(setOf(eye), pose(runtime, "b").locked)

        // An explicit field in the same patch wins over the link.
        val next = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(next.projectId, next.state, "Leave mesh-only",
            listOf(settings("meshOnly" to false, "generateDeformers" to false)), MutationAuthor.AGENT)
        val raw = runtime.capture().document.settings
        assertFalse(raw.getValue("meshOnly").jsonPrimitive.boolean)
        assertFalse(raw.getValue("generateDeformers").jsonPrimitive.boolean)
        assertFalse(runtime.capture().document.config().generateDeformers)
    }

    @Test fun offThenOnInOneBatchStillReleasesThePoseInOneAtomicCommit() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_X
        posed(runtime, mapOf(angle to 15f), emptySet(), setOf(angle))
        val before = runtime.capture(); val history = runtime.history()
        val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Idle off and on",
            listOf(settings("motionIdle" to false), settings("motionIdle" to true)), MutationAuthor.USER)
        // The final settings equal the start, so no history node; the release in between is still published.
        assertTrue(result.applied)
        assertEquals(before.document.settings, runtime.capture().document.settings)
        assertEquals(history, runtime.history())
        assertEquals(default(runtime, angle), pose(runtime, "a").values.getValue(angle))
        assertEquals(15f, pose(runtime, "b").values.getValue(angle))
        assertNotEquals(before.state, runtime.capture().state)
    }

    @Test fun aFailingLaterMemberPublishesNeitherSettingsNorPoses() = runBlocking {
        val runtime = runtime()
        posed(runtime, mapOf(StandardParameters.ANGLE_X to 15f), emptySet(), emptySet())
        val before = runtime.capture(); val history = runtime.history()
        assertFailsWith<WorkspaceBatchEditException> {
            WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Mesh only then invalid",
                listOf(settings("meshOnly" to true), settings("meshSpacing" to 0)), MutationAuthor.USER)
        }
        assertEquals(before, runtime.capture())
        assertEquals(history, runtime.history())
    }

    @Test fun motionOptionsKeepExportMotionsInStepUnlessNamedAndNodLeavesTheAuthoredPose() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_Y
        posed(runtime, mapOf(angle to 12f), emptySet(), emptySet())
        val commands = WorkspaceDocumentCommands(runtime)
        suspend fun apply(vararg fields: Pair<String, Any>) = runtime.capture().let {
            commands.execute(it.projectId, it.state, "Motion options", listOf(settings(*fields)), MutationAuthor.USER)
        }
        apply("motionNod" to false, "motionShake" to false)
        // Nod and Shake play transient frames only; the authored pose is not theirs to reset.
        assertEquals(12f, pose(runtime, "a").values.getValue(angle))
        apply("motionIdle" to false, "motionBlink" to false, "motionSkeleton" to false)
        assertFalse(runtime.capture().document.settings.getValue("exportMotions").jsonPrimitive.boolean)
        apply("motionBlink" to true)
        assertTrue(runtime.capture().document.settings.getValue("exportMotions").jsonPrimitive.boolean)
        apply("motionIdle" to true, "exportMotions" to false)
        assertFalse(runtime.capture().document.settings.getValue("exportMotions").jsonPrimitive.boolean)
        assertFalse(runtime.capture().document.config().exportMotions)
    }

    @Test fun physicsOffReleasesItsOutputsAndGenerationSettingsCommandsShareTheIntent() = runBlocking {
        val runtime = runtime()
        val commands = WorkspaceDocumentCommands(runtime)
        val setup = runtime.capture()
        commands.execute(setup.projectId, setup.state, "Physics", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "SwayIn"); put("name", "In") }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "SwayOut"); put("name", "Out") }),
            WorkspaceDocumentOperation("physics_put", buildJsonObject {
                put("id", "Sway"); put("name", "Sway")
                putJsonArray("inputs") { add(buildJsonObject { put("parameter", "SwayIn"); put("type", "angle"); put("weight", 100) }) }
                putJsonArray("outputs") { add(buildJsonObject { put("parameter", "SwayOut"); put("vertex", 1); put("scale", 1) }) }
            })), MutationAuthor.AGENT)
        val output = ParameterId("SwayOut"); val input = ParameterId("SwayIn")
        posed(runtime, mapOf(output to 0.5f, input to 0.5f), emptySet(), setOf(output))
        val before = runtime.capture()
        var projected: Map<String, WorkspacePose>? = null
        val result = WorkspaceGenerationCommands(runtime).execute(before.projectId, before.state, settings("generatePhysics" to false),
            "Physics off", MutationAuthor.USER, poses = { projected = it })
        assertTrue(result.commit.applied)
        assertEquals(default(runtime, output), pose(runtime, "a").values.getValue(output))
        assertEquals(0.5f, pose(runtime, "a").values.getValue(input))
        assertEquals(0.5f, pose(runtime, "b").values.getValue(output))
        assertEquals(mapOf("a" to pose(runtime, "a")), projected)
    }

    /** A GUI field session's draft, committed through the same queue and preparation as the desktop editor. */
    private suspend fun submitDraft(runtime: WorkspaceRuntime<RigPreviewModel>, document: WorkspaceDocument,
                                    intents: List<JsonObject>, poses: (Map<String, WorkspacePose>) -> Unit = {}): WorkspaceCommit<RigPreviewModel> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val before = runtime.capture(); val commands = WorkspaceGenerationCommands(runtime)
            return WorkspaceDraftQueue(runtime, scope).submit(before.projectId, before.state, document, "Workspace changed in the editor",
                MutationAuthor.USER, beforeCommit = { _, _, _, _ -> },
                prepare = { draft, model, gui -> commands.prepareEditorDraft(draft, model, gui, intents) },
                auxiliary = { captured, draft, model ->
                    WorkspaceDocumentCommands.changedPoses(captured, draft, model).takeIf { it.isNotEmpty() }?.let(poses)
                }).await()
        } finally { scope.cancel() }
    }

    private fun changes(vararg fields: Pair<String, Boolean>) = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }

    @Test fun offThenOnInsideOneFieldSessionStillReleasesThePoseInOneCommit() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_X
        posed(runtime, mapOf(angle to 15f), emptySet(), setOf(angle))
        val before = runtime.capture(); val history = runtime.history()
        var projected: Map<String, WorkspacePose>? = null
        // The session's final settings equal the start; only the recorded switches show the off in between.
        val result = submitDraft(runtime, before.document, listOf(changes("motionIdle" to false), changes("motionIdle" to true))) { projected = it }
        assertTrue(result.applied)
        assertEquals(before.document.settings, runtime.capture().document.settings)
        assertEquals(history, runtime.history())
        assertEquals(default(runtime, angle), pose(runtime, "a").values.getValue(angle))
        assertEquals(15f, pose(runtime, "b").values.getValue(angle))
        assertEquals(mapOf("a" to pose(runtime, "a")), projected)
        assertNotEquals(before.state, runtime.capture().state)
    }

    @Test fun fieldSessionSwitchesLinkFieldsAsThePublicCommandAndKeepOtherEditsInOneNode() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_X
        posed(runtime, mapOf(angle to 15f), emptySet(), emptySet())
        val before = runtime.capture(); val nodes = runtime.history().selections.size
        val public = WorkspaceSettingsIntent.parse(before.document, before.model, changes("meshOnly" to true)).settings
        assertFalse(public.getValue("generateDeformers").jsonPrimitive.boolean)
        // The GUI's local copy of the link disagrees with the intent; it also edited an unrelated field.
        val gui = before.document.copy(settings = JsonObject(before.document.settings + mapOf(
            "meshOnly" to JsonPrimitive(true), "generateDeformers" to JsonPrimitive(true), "meshEdgeMode" to JsonPrimitive("TRIPLE"))))
        val result = submitDraft(runtime, gui, listOf(changes("meshOnly" to true)))
        assertTrue(result.applied)
        assertEquals(nodes + 1, runtime.history().selections.size)
        val settings = runtime.capture().document.settings
        assertTrue(settings.getValue("meshOnly").jsonPrimitive.boolean)
        assertEquals(public.getValue("generateDeformers"), settings.getValue("generateDeformers"))
        assertEquals("TRIPLE", settings.getValue("meshEdgeMode").jsonPrimitive.content)
        assertEquals(default(runtime, angle), pose(runtime, "a").values.getValue(angle))
        assertEquals(default(runtime, angle), pose(runtime, "b").values.getValue(angle))
    }

    @Test fun aFieldSessionWithoutSwitchesKeepsTheDocumentOnlyDraft() = runBlocking {
        val runtime = runtime()
        val angle = StandardParameters.ANGLE_X
        posed(runtime, mapOf(angle to 15f), emptySet(), emptySet())
        val before = runtime.capture()
        val gui = before.document.copy(settings = JsonObject(before.document.settings + ("meshEdgeMode" to JsonPrimitive("TRIPLE"))))
        assertTrue(submitDraft(runtime, gui, emptyList()).applied)
        assertEquals(before.auxiliary, runtime.capture().auxiliary)
        assertEquals(15f, pose(runtime, "a").values.getValue(angle))
        val unchanged = runtime.capture()
        assertFalse(submitDraft(runtime, unchanged.document, emptyList()).applied)
        assertEquals(unchanged, runtime.capture())
    }

    @Test fun aRejectedFieldSessionSwitchPublishesNeitherSettingsNorPoses() = runBlocking {
        val runtime = runtime()
        posed(runtime, mapOf(StandardParameters.ANGLE_X to 15f), emptySet(), emptySet())
        val before = runtime.capture(); val history = runtime.history()
        assertFailsWith<IllegalArgumentException> {
            submitDraft(runtime, before.document, listOf(changes("motionIdle" to false), buildJsonObject { put("meshSpacing", 0) }))
        }
        assertEquals(before, runtime.capture())
        assertEquals(history, runtime.history())
    }
}
