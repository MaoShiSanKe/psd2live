package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class WorkspacePoseTimelineCommandsTest {
    private val axis = ParameterId("Axis")
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Timeline", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", axis.raw); put("name", "Axis"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("motion_put", buildJsonObject { put("clip", MotionClips.toJson(MotionClip("take", "Take", duration = 2f))) })), MutationAuthor.USER)
        return runtime
    }
    private fun request(value: Float, time: Float = 1f) = buildJsonObject {
        putJsonObject("values") { put(axis.raw, value) }
        putJsonObject("auto_key") { put("clip_id", "take"); put("time", time) }
    }
    private fun pose(runtime: WorkspaceRuntime<RigPreviewModel>) = PreviewSessions.read(runtime.capture().model.rig.puppet.parameters,
        runtime.capture().auxiliary, "first")

    @Test fun autoKeyAndPoseShareOneStateAndHistoryTransitionAndUseTheAuthoredInitialValue() = runBlocking<Unit> {
        val runtime = fixture(); var capture = runtime.capture()
        WorkspacePreviewCommands(runtime).edit(capture.projectId, capture.state, "first", buildJsonObject {
            put("mode", "set"); putJsonObject("values") { put(axis.raw, 0.4f) }
        })
        capture = runtime.capture(); val history = runtime.history()
        val result = WorkspacePoseCommands(runtime).execute(capture.projectId, capture.state, "first", request(0.8f), MutationAuthor.USER)
        val committed = runtime.capture()
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
        assertEquals(capture.state.substringAfterLast(':').toLong() + 1, committed.state.substringAfterLast(':').toLong())
        val curve = committed.document.rigEdits.motionClips.single().curve(axis.raw)!!
        assertEquals(listOf(0f, 1f), curve.keys.map { it.time }); assertEquals(listOf(0.4f, 0.8f), curve.keys.map { it.value })
        assertEquals(0.8f, pose(runtime).values.getValue(axis)); assertEquals(committed.state, result.getValue("state").jsonPrimitive.content)
        assertEquals(2, result.getValue("keyed").jsonArray.size)
        validateOperationSchema(result, WorkspaceAuthoringResultSchemas.forOperation("preview_pose")!!)
        val noop = WorkspacePoseCommands(runtime).execute(committed.projectId, committed.state, "first", request(0.8f), MutationAuthor.AGENT)
        assertEquals(committed, runtime.capture()); assertTrue(noop.getValue("keyed").jsonArray.isEmpty())
        assertEquals(curve, builder.build(committed.document).config.rigEdits.motionClips.single().curve(axis.raw))
    }

    @Test fun invalidAutoKeyStaleStateRejectedProjectionAndCancellationPublishNeitherPoseNorKeys() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val command = WorkspacePoseCommands(runtime)
        assertFailsWith<IllegalArgumentException> { command.execute(before.projectId, before.state, "first", request(0.7f, 5f), MutationAuthor.AGENT) }
        assertFailsWith<WorkspaceConflict> { command.execute(before.projectId, "stale", "first", request(0.7f), MutationAuthor.AGENT) }
        assertFailsWith<IllegalStateException> { command.execute(before.projectId, before.state, "first", request(0.7f), MutationAuthor.AGENT) { _, _, _, _ -> error("Reject projection") } }
        val job = Job().also { it.cancel() }
        assertFailsWith<CancellationException> { withContext(job) { command.execute(before.projectId, before.state, "first", request(0.7f), MutationAuthor.AGENT) } }
        assertEquals(before, runtime.capture())
        val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
        assertFailsWith<WorkspaceConflict> { command.execute(before.projectId, before.state, "first", request(0.7f), MutationAuthor.AGENT) }
        assertEquals(foreign, runtime.capture())
    }

    @Test fun timelineSelectionMovesReplacementsCopyPasteAndDeleteUseOrderedCandidatesAndRollbackLateFailures() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        fun operation(mode: String, fields: JsonObject) = WorkspaceDocumentOperation("motion_$mode", JsonObject(fields + mapOf("id" to JsonPrimitive("take"))))
        val authored = commands.execute(root.projectId, root.state, "Keys", listOf(operation("pose", buildJsonObject {
            put("time", 0.5f); putJsonObject("values") { put(axis.raw, 0.2f) }
        })), MutationAuthor.USER).capture
        val selected = buildJsonArray { add(buildJsonObject { put("parameter", axis.raw); put("time", 0.5f) }) }
        val moved = operation("move_keys", buildJsonObject { put("selection", selected); put("dt", 0.5f); put("dv", 0.1f); put("normalized", true) })
        assertFails { commands.execute(authored.projectId, authored.state, "Late failure", listOf(moved,
            operation("delete_keys", buildJsonObject { put("selection", selected) })), MutationAuthor.AGENT) }
        assertEquals(authored, runtime.capture())
        val result = commands.execute(authored.projectId, authored.state, "Move", listOf(moved), MutationAuthor.USER).capture
        val key = result.document.rigEdits.motionClips.single().curves.single().keys.single()
        assertEquals(1f, key.time); assertEquals(0.4f, key.value)
        val copied = MotionKeyEdits.copy(result.document.rigEdits.motionClips.single(), setOf(MotionKeyRef(axis.raw, 1f)))
        val pasted = operation("paste_keys", buildJsonObject {
            put("time", 1.5f)
            putJsonArray("keys") {
                copied.forEach { (id, key) -> add(buildJsonObject {
                    put("parameter", id)
                    putJsonObject("key") { put("time", key.time); put("value", key.value) }
                }) }
            }
        })
        val replacements = operation("replace_keys", buildJsonObject { putJsonArray("keys") { add(buildJsonObject {
            put("parameter", axis.raw); put("from_time", 1f); putJsonObject("key") { put("time", 1.25f); put("value", 0.6f); put("interpolation", "LINEAR") }
        }) } })
        val edited = commands.execute(result.projectId, result.state, "Paste and replace", listOf(pasted, replacements), MutationAuthor.USER).capture
        assertEquals(listOf(1.25f, 1.5f), edited.document.rigEdits.motionClips.single().curves.single().keys.map { it.time })
        assertEquals(MotionInterpolation.LINEAR, edited.document.rigEdits.motionClips.single().curves.single().keys.first().interpolation)
        runtime.checkout(edited.projectId, edited.state, authored.historyHead)
        assertEquals(authored.document, runtime.capture().document)
        runtime.checkout(edited.projectId, runtime.capture().state, edited.historyHead)
        assertEquals(edited.document, runtime.capture().document)
    }

    @Test fun processPlaybackAndTrackingRespectLocksDoNotDirtyDurableStateAndResetOnProjectReload() = runBlocking<Unit> {
        val runtime = fixture(); var capture = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(capture.projectId, capture.state, "Playback curve", listOf(WorkspaceDocumentOperation("motion_set_key", buildJsonObject {
            put("id", "take"); put("parameter", axis.raw); putJsonObject("key") { put("time", 0f); put("value", 0.9f) }
        })), MutationAuthor.USER)
        capture = runtime.capture()
        WorkspacePreviewCommands(runtime).edit(capture.projectId, capture.state, "first", buildJsonObject {
            put("mode", "set"); putJsonObject("values") { put(axis.raw, 0.2f) }; putJsonObject("locks") { put(axis.raw, true) }
        })
        val before = runtime.capture(); val history = runtime.history(); val sessions = WorkspacePlaybackSessions(runtime)
        sessions.configure(before.projectId, before.state, "first", buildJsonObject { put("mode", "start"); put("clip_id", "take") })
        val frame = sessions.frame("first", 0.5f)
        assertEquals(0.5f, frame.getValue("time").jsonPrimitive.float); assertEquals(0.2f, frame.getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
        val tracked = sessions.configure(before.projectId, before.state, "first", buildJsonObject {
            put("mode", "tracking"); put("enabled", true); put("pointer", buildJsonArray { add(1f); add(0f) })
        })
        assertEquals(30f, tracked.getValue("values").jsonObject.getValue(StandardParameters.ANGLE_X.raw).jsonPrimitive.float)
        assertFalse(sessions.frame("second", 0f).getValue("playing").jsonPrimitive.boolean)
        assertTrue(sessions.frame("first", 1f).getValue("playing").jsonPrimitive.boolean)
        assertFalse(sessions.frame("first", 1f).getValue("playing").jsonPrimitive.boolean)
        validateOperationSchema(tracked, WorkspaceAuthoringResultSchemas.forOperation("preview_playback")!!)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        runtime.install(before.state, before.projectId, before.document, before.model, history, before.auxiliary, discardUnsaved = true)
        assertFalse(sessions.frame("first", 0f).getValue("tracking").jsonPrimitive.boolean)
    }

    @Test fun idleClockProducesACompletePoseAndStopsWithoutChangingTheAuthoredPose() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        val model = root.model.copy(config = root.model.config.copy(meshOnly = false))
        val capture = runtime.install(root.state, root.projectId, root.document, model, runtime.history(), root.auxiliary, discardUnsaved = true)
        val sessions = WorkspacePlaybackSessions(runtime)
        sessions.configure(capture.projectId, capture.state, "first", buildJsonObject { put("mode", "animation"); put("enabled", true) })
        val frame = sessions.frame("first", 0.5f)
        assertTrue(frame.getValue("animation").jsonPrimitive.boolean)
        assertEquals(0.5f, frame.getValue("elapsed").jsonPrimitive.float)
        assertEquals(model.rig.puppet.parameters.map { it.id.raw }.toSet(), frame.getValue("values").jsonObject.keys)
        assertNotEquals(0f, frame.getValue("values").jsonObject.getValue(StandardParameters.BREATH.raw).jsonPrimitive.float)
        sessions.configure(capture.projectId, capture.state, "first", buildJsonObject { put("mode", "animation"); put("enabled", false) })
        val stopped = sessions.frame("first", 0.5f)
        assertFalse(stopped.getValue("animation").jsonPrimitive.boolean)
        assertEquals(0.5f, stopped.getValue("elapsed").jsonPrimitive.float)
        assertEquals(pose(runtime).values.getValue(StandardParameters.BREATH), stopped.getValue("values").jsonObject.getValue(StandardParameters.BREATH.raw).jsonPrimitive.float)
        assertEquals(capture, runtime.capture())
    }

    @Test fun authoredRestartKeepsTrackingAndPlayheadAndReopeningRejectsTheOldPointer() = runBlocking<Unit> {
        val runtime = fixture(); val capture = runtime.capture(); val sessions = WorkspacePlaybackSessions(runtime)
        fun control(mode: String, fields: JsonObject = JsonObject(emptyMap())) = sessions.configure(
            capture.projectId, runtime.capture().state, "first", JsonObject(fields + ("mode" to JsonPrimitive(mode))))
        control("start", buildJsonObject { put("clip_id", "take"); put("time", 0.5f) })
        control("tracking", buildJsonObject { put("enabled", true) })
        sessions.pointer("first", 0.5f to -0.25f)
        val before = runtime.capture(); val history = runtime.history()
        val restarted = sessions.restart(before.projectId, before.state, "first")
        assertFalse(restarted.getValue("playing").jsonPrimitive.boolean)
        assertFalse(restarted.getValue("animation").jsonPrimitive.boolean)
        assertTrue(restarted.getValue("tracking").jsonPrimitive.boolean)
        assertEquals(0.5f, restarted.getValue("time").jsonPrimitive.float)
        assertEquals("take", restarted.getValue("clip_id").jsonPrimitive.content)
        assertEquals(15f, restarted.getValue("values").jsonObject.getValue(StandardParameters.ANGLE_X.raw).jsonPrimitive.float)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        runtime.install(before.state, before.projectId, before.document, before.model, history, before.auxiliary, discardUnsaved = true)
        sessions.pointer("first", 1f to 1f)
        val reloaded = sessions.frame("first", 0f)
        assertFalse(reloaded.getValue("tracking").jsonPrimitive.boolean)
        assertFalse(reloaded.getValue("pointer_active").jsonPrimitive.boolean)
        assertNull(reloaded["clip_id"])
        assertFailsWith<WorkspaceConflict> { sessions.restart(before.projectId, before.state, "first") }
    }

    @Test fun generatedMotionKnobsAndPresetLifecycleArePersistentSharedCandidates() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        fun preset(action: String, values: JsonObject? = null) = WorkspaceDocumentOperation("motion_preset", buildJsonObject {
            put("builtin", "Idle"); put("action", action); values?.let { put("values", it) }
        })
        val changed = commands.execute(before.projectId, before.state, "Tune idle", listOf(preset("update", buildJsonObject { put("speed", 2f) })), MutationAuthor.USER).capture
        assertEquals(2f, changed.document.rigEdits.motionPresets.getValue("Idle").values.getValue("speed"))
        assertFails { commands.execute(changed.projectId, changed.state, "Bad knob", listOf(preset("update", buildJsonObject { put("missing", 1) })), MutationAuthor.AGENT) }
        assertEquals(changed, runtime.capture())
        val deleted = commands.execute(changed.projectId, changed.state, "Delete", listOf(preset("delete")), MutationAuthor.USER).capture
        assertTrue(deleted.document.rigEdits.motionPresets.getValue("Idle").deleted)
        val restored = commands.execute(deleted.projectId, deleted.state, "Restore", listOf(preset("restore")), MutationAuthor.USER).capture
        assertTrue(restored.document.rigEdits.motionPresets.isEmpty())
    }

    @Test fun fkIkTargetMetadataAndPoseCommitTogetherAndKeepTheOriginalGestureState() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val spec = SkeletonSpec().withCustomBone(0f, 0f, 0f, 40f).withCustomBone(0f, 40f, 0f, 80f, "custom_1")
        val commands = WorkspaceDocumentCommands(runtime)
        val setup = commands.execute(before.projectId, before.state, "Skeleton", spec.bones.map { bone ->
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", bone.parameterId); put("name", bone.name); put("min", -180); put("max", 180) })
        } + WorkspaceDocumentOperation("skeleton_put", buildJsonObject { put("spec", spec.toJson()) }), MutationAuthor.USER).capture
        val poseCommands = WorkspacePoseCommands(runtime)
        val fk = poseCommands.execute(setup.projectId, setup.state, "first", buildJsonObject {
            put("bone_id", "custom_1"); put("target", buildJsonArray { add(20); add(20) }); put("ik", false)
        }, MutationAuthor.USER)
        assertTrue(pose(runtime).values.getValue(ParameterId(spec.bones.first().parameterId)) != 0f)
        assertEquals(setup.historyHead, runtime.capture().historyHead)
        val current = runtime.capture(); val history = runtime.history()
        val constrained = poseCommands.execute(current.projectId, current.state, "first", buildJsonObject {
            putJsonObject("ik_target") { put("bone_id", "custom_2"); put("point", buildJsonArray { add(30); add(60) }) }
            putJsonObject("bone_ik") { put("bone_id", "custom_2"); put("settings", SkeletonIkSettings(iterations = 128).toJson()) }
            putJsonObject("auto_key") { put("clip_id", "take"); put("time", 1) }
        }, MutationAuthor.USER)
        val capture = runtime.capture()
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
        assertEquals(SkeletonIkTarget(30f, 60f), capture.document.rigEdits.skeleton!!.ikTargets.getValue("custom_2"))
        assertEquals(128, capture.document.rigEdits.skeleton!!.bone("custom_2")!!.ik.iterations)
        assertTrue(constrained.getValue("keyed").jsonArray.isNotEmpty())
        val tip = SkeletonPoseSolver.posed(capture.model.rig.puppet, capture.document.rigEdits.skeleton, pose(runtime).values).last()
        assertTrue(kotlin.math.hypot(tip.tailX - 30f, tip.tailY - 60f) < 0.5f)
        assertEquals(capture.document.rigEdits.skeleton, builder.build(capture.document).config.rigEdits.skeleton)
        assertFailsWith<WorkspaceConflict> { poseCommands.execute(current.projectId, current.state, "first", request(0.5f), MutationAuthor.USER) }
        assertFailsWith<IllegalArgumentException> { poseCommands.execute(capture.projectId, capture.state, "first", buildJsonObject {
            put("bone_id", "custom_1"); put("target", buildJsonArray { add(0) })
        }, MutationAuthor.AGENT) }
        assertEquals(capture, runtime.capture()); assertEquals(current.state, fk.getValue("state").jsonPrimitive.content)
    }

    @Test fun idleBlinkFollowingAndOneShotUseTheProcessClockWithoutReplacingTheAuthoredPose() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Animation", listOf(
            WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") {
                put("meshOnly", false); put("motionBasic", true); put("motionIdle", true); put("motionBlink", true)
            } }),
            WorkspaceDocumentOperation("parameter_update", buildJsonObject {
                put("parameter_id", StandardParameters.EYE_L_OPEN.raw); put("name", "Eye"); put("min", 0); put("max", 1); put("default", 1)
            }),
            WorkspaceDocumentOperation("motion_set_key", buildJsonObject {
                put("id", "take"); put("parameter", axis.raw); putJsonObject("key") { put("time", 0); put("value", 0.6f) }
            }),
        ), MutationAuthor.USER)
        val current = runtime.capture(); val sessions = WorkspacePlaybackSessions(runtime)
        fun control(fields: JsonObject) = sessions.configure(current.projectId, current.state, "first", fields)
        fun value(frame: JsonObject, id: ParameterId) = frame.getValue("values").jsonObject.getValue(id.raw).jsonPrimitive.float
        control(buildJsonObject { put("mode", "animation"); put("enabled", true) })
        val idle = sessions.frame("first", 0.25f)
        assertTrue(kotlin.math.abs(value(idle, StandardParameters.ANGLE_X)) > 0.01f)
        control(buildJsonObject { put("mode", "tracking"); put("enabled", true); put("smooth", true); put("pointer", buildJsonArray { add(1); add(0) }) })
        val following = sessions.frame("first", 0.05f)
        assertTrue(value(following, StandardParameters.ANGLE_X) > value(idle, StandardParameters.ANGLE_X))
        assertTrue(value(following, StandardParameters.ANGLE_X) < 30f)
        control(buildJsonObject { put("mode", "trigger"); put("name", "Take") })
        assertEquals(0.6f, value(sessions.frame("first", 0.5f), axis))
        assertEquals("take", sessions.frame("first", 1f).getValue("active_motion").jsonPrimitive.content)
        assertNull(sessions.frame("first", 1f)["active_motion"])
        assertEquals(0f, value(sessions.frame("first", 0f), axis))
        control(buildJsonObject { put("mode", "reset") })
        control(buildJsonObject { put("mode", "animation"); put("enabled", true) })
        repeat(4) { sessions.frame("first", 1f) }
        assertTrue(value(sessions.frame("first", 0.32f), StandardParameters.EYE_L_OPEN) < 0.1f)
        assertEquals(current, runtime.capture())
        assertEquals(0f, pose(runtime).values.getValue(axis))
        assertEquals(1f, pose(runtime).values.getValue(StandardParameters.EYE_L_OPEN))
        assertFailsWith<IllegalArgumentException> { control(buildJsonObject {
            put("mode", "tracking"); put("enabled", true); put("pointer", buildJsonArray { add(1) })
        }) }
    }

    @Test fun clipLifecycleCandidatesGenerateStableNamesAndTrimShortenedTimelinesAndReplay() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        fun operation(mode: String, fields: JsonObject) = WorkspaceDocumentOperation("motion_$mode", fields)
        val created = commands.execute(root.projectId, root.state, "Clips", listOf(
            operation("create", buildJsonObject { put("id", "copy"); put("name", "Take") }),
            operation("duplicate", buildJsonObject { put("id", "take"); put("new_id", "duplicate") }),
            operation("rename", buildJsonObject { put("id", "duplicate"); put("name", "Take 2") }),
            operation("set_key", buildJsonObject { put("id", "copy"); put("parameter", axis.raw); putJsonObject("key") { put("time", 1.5f); put("value", 0.5f) } }),
            operation("properties", buildJsonObject { put("id", "copy"); put("duration", 1); put("loop", true); put("fps", 24); put("fade_in", 0.2f); put("enabled", false) }),
        ), MutationAuthor.USER).capture
        assertEquals(listOf("Take", "Take 2", "Take 2 2"), created.document.rigEdits.motionClips.map { it.name })
        val clip = created.document.rigEdits.motionClips.single { it.id == "copy" }
        assertEquals(listOf(MotionKey(1f, 0.5f)), clip.curves.single().keys)
        assertEquals(1f, clip.duration); assertEquals(24f, clip.fps)
        assertTrue(clip.loop); assertFalse(clip.enabled); assertEquals(0.2f, clip.fadeIn)
        assertFailsWith<IllegalArgumentException> { commands.execute(created.projectId, created.state, "Bad duplicate", listOf(
            operation("duplicate", buildJsonObject { put("id", "take"); put("new_id", "copy") })), MutationAuthor.AGENT) }
        assertEquals(created, runtime.capture())
        runtime.checkout(created.projectId, created.state, root.historyHead)
        runtime.checkout(created.projectId, runtime.capture().state, created.historyHead)
        assertEquals(created.document, runtime.capture().document)
    }

    @Test fun previewPhysicsMatchesIndependentEngineRespectsLocksAndOwnsOnlyTransientWorkspaceState() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val setting = RigPhysicsEdit("spring", "Spring", listOf(PhysicsInput(axis.raw, type = PhysicsSourceType.X)),
            listOf(PhysicsOutput(StandardParameters.ANGLE_X.raw, scale = 0.5f)), listOf(PhysicsSegment(8f)))
        commands.execute(root.projectId, root.state, "Physics preview", listOf(
            WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("meshOnly", false); put("generatePhysics", true) } }),
            WorkspaceDocumentOperation("physics_put", setting.toJson()),
        ), MutationAuthor.USER)
        var before = runtime.capture(); val sessions = WorkspacePlaybackSessions(runtime)
        val engine = PhysicsEngine(listOf(setting), PhysicsEngine.ranges(before.model.rig.puppet.parameters), before.document.rigEdits.physicsFps.toFloat())
        val inputs = pose(runtime).values + (axis to 0.8f)
        val request = buildJsonObject { put("dt", 1f / 60f); putJsonObject("values") { put(axis.raw, 0.8f) } }
        repeat(20) {
            val expected = engine.step(inputs.mapKeys { it.key.raw }, 1f / 60f)
            val result = sessions.physics(before.projectId, before.state, "first", request)
            expected.forEach { (id, value) -> assertEquals(value, result.getValue("outputs").jsonObject.getValue(id).jsonPrimitive.float, 0.00001f) }
            validateOperationSchema(result, WorkspaceAuthoringResultSchemas.forOperation("preview_physics")!!)
        }
        assertEquals(before, runtime.capture()); assertEquals(0f, pose(runtime).values.getValue(axis))
        val fresh = sessions.physics(before.projectId, before.state, "second", request)
        val reset = sessions.physics(before.projectId, before.state, "first", JsonObject(request + ("reset" to JsonPrimitive(true))))
        assertEquals(fresh.getValue("outputs"), reset.getValue("outputs"))
        WorkspacePreviewCommands(runtime).edit(before.projectId, before.state, "first", buildJsonObject {
            put("mode", "set"); putJsonObject("locks") { put(StandardParameters.ANGLE_X.raw, true) }
        })
        before = runtime.capture()
        val locked = sessions.physics(before.projectId, before.state, "first", request)
        assertFalse(StandardParameters.ANGLE_X.raw in locked.getValue("outputs").jsonObject)
        assertEquals(0f, locked.getValue("values").jsonObject.getValue(StandardParameters.ANGLE_X.raw).jsonPrimitive.float)
        assertEquals(before, runtime.capture())
        assertFailsWith<WorkspaceConflict> { sessions.physics(before.projectId, root.state, "first", request) }
    }

    @Test fun processJobRetainsItsAtomicTerminalPoseAfterLateFailureAndRequestRetriesReturnTheSameJob() = runBlocking<Unit> {
        val runtime = fixture()
        val host = object : WorkspaceBackendStub() {
            override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
            override fun snapshot() = captureQueries().snapshot()
            override suspend fun authorPose(arguments: JsonObject, author: MutationAuthor): JsonObject {
                val capture = runtime.capture()
                WorkspacePoseCommands(runtime).execute(capture.projectId, arguments.getValue("state").jsonPrimitive.content, "first", arguments, author)
                throw CancellationException("Late host refresh cancelled")
            }
        }
        WorkspaceOperations(host).use { operations ->
            val before = runtime.capture(); val history = runtime.history()
            val input = JsonObject(request(0.7f) + buildJsonObject { put("project_id", before.projectId); put("state", before.state); put("request_id", "pose-once") })
            val actor = WorkspaceOperationContext(MutationAuthor.AGENT)
            val job = operations.registry.invoke("preview_pose", input, actor).data
            val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, actor).data
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            assertEquals(job.getValue("id"), operations.registry.invoke("preview_pose", input, actor).data.getValue("id"))
            assertEquals(history.selections.size + 1, runtime.history().selections.size)
            assertEquals(0.7f, pose(runtime).values.getValue(axis))
            assertEquals(runtime.capture().state, terminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
            validateOperationSchema(terminal.getValue("result"), operations.registry.definition("preview_pose").jobResultSchema!!)
        }
    }
    @Test fun trackingAlgorithmIsIndependentOfAnimationAndClipSelectionAndCanBeDisabled() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        val capture = runtime.install(root.state, root.projectId, root.document,
            root.model.copy(config = root.model.config.copy(meshOnly = false)), runtime.history(), root.auxiliary, discardUnsaved = true)
        val sessions = WorkspacePlaybackSessions(runtime)
        fun control(mode: String, fields: JsonObject = JsonObject(emptyMap())) = sessions.configure(
            capture.projectId, capture.state, "first", JsonObject(fields + ("mode" to JsonPrimitive(mode))))
        fun value(frame: JsonObject, id: String) = frame.getValue("values").jsonObject.getValue(id).jsonPrimitive.float
        control("animation", buildJsonObject { put("enabled", true) })
        val direct = control("tracking", buildJsonObject {
            put("enabled", true); put("pointer", buildJsonArray { add(0.5f); add(-0.5f) })
        })
        assertFalse(direct.getValue("smooth_tracking").jsonPrimitive.boolean)
        assertEquals(15f, value(sessions.frame("first", 0.1f), "ParamAngleX"))
        val selected = control("seek", buildJsonObject { put("clip_id", "take"); put("time", 0f) })
        assertEquals(15f, value(selected, "ParamAngleX"))
        control("tracking", buildJsonObject { put("enabled", true); put("smooth", true) })
        val smooth = sessions.frame("first", 0.1f)
        assertTrue(value(smooth, "ParamAngleX") in 0f..19f)
        assertTrue(value(smooth, "ParamAngleY") < 0f)
        assertTrue(value(smooth, "ParamBodyAngleY") < 0f)
        control("animation", buildJsonObject { put("enabled", true) })
        val idle = sessions.frame("first", 0f)
        assertEquals(value(smooth, "ParamAngleX"), value(idle, "ParamAngleX"))
        assertEquals(value(smooth, "ParamBodyAngleY"), value(idle, "ParamBodyAngleY"))
        assertTrue(sessions.restart(capture.projectId, capture.state, "first").getValue("smooth_tracking").jsonPrimitive.boolean)
        val stopped = control("tracking", buildJsonObject { put("enabled", false) })
        sessions.pointer("first", 1f to 1f)
        assertEquals(stopped.getValue("values"), sessions.frame("first", 0.1f).getValue("values"))
        assertEquals(capture, runtime.capture())
    }

}
