package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot
import kotlin.math.max

/** Simulation edits on the overlay, shared by the GUI and MCP. */
object SimAuthoring {
    /** [arguments] laid over the simulation with their `id`, or a new one; validated against [model]. */
    fun put(overlay: RigEditOverlay, model: PuppetModel, arguments: JsonObject): RigEditOverlay {
        val id = requireNotNull(arguments["id"]?.jsonPrimitive?.contentOrNull) { "id is required" }
        val existing = overlay.simEdits.firstOrNull { it.id == id }
        val edit = existing?.patched(arguments) ?: RigSimEdit.fromJson(arguments)
        return put(overlay, model, edit)
    }

    fun put(overlay: RigEditOverlay, model: PuppetModel, edit: RigSimEdit): RigEditOverlay {
        validate(model, edit)
        val index = overlay.simEdits.indexOfFirst { it.id == edit.id }
        val next = if (index < 0) overlay.simEdits + edit else overlay.simEdits.toMutableList().also { it[index] = edit }
        return overlay.copy(simEdits = next)
    }

    fun remove(overlay: RigEditOverlay, id: String): RigEditOverlay {
        require(overlay.simEdits.any { it.id == id }) { "Simulation not found: $id" }
        return overlay.copy(simEdits = overlay.simEdits.filterNot { it.id == id })
    }

    /** A fresh ID for a simulation on [targets]. */
    fun nextId(overlay: RigEditOverlay, stem: String = "Sim"): String {
        val used = overlay.simEdits.mapTo(HashSet()) { it.id }
        return generateSequence(1) { it + 1 }.map { "$stem$it" }.first { it !in used }
    }

    /** Throws when [edit] cannot run on [model]: missing meshes, parameters or glue keys. */
    fun validate(model: PuppetModel, edit: RigSimEdit) {
        val parameters = model.parameters.mapTo(HashSet()) { it.id.raw }
        edit.inputs.forEach { require(it.parameter in parameters) { "Parameter ${it.parameter} does not exist" } }
        val glues = model.glues.mapTo(HashSet(), ::glueKey)
        edit.glueRoles.keys.forEach { require(it in glues) { "No glue $it; glue keys are meshA|meshB as inspect lists them" } }
        SimScene.build(model, edit)
    }

    /**
     * Runs [edit] on [model] and reports how it behaves: settles at the default pose, then for each input
     * holds it at its maximum for [hold] seconds and releases it, and finally applies [wind] (px/s², world)
     * if given. Read-only; nothing is baked.
     */
    fun report(model: PuppetModel, edit: RigSimEdit, hold: Float = 0.5f, release: Float = 1.5f, wind: Pair<Float, Float>? = null): JsonObject {
        val scene = SimScene.build(model, edit)
        val fps = 60
        val dt = 1f / fps
        val residual = scene.calibrate(model)
        val rest = scene.state.positions()
        var worstStretch = 0f
        fun runPhase(pose: Map<ParameterId, Float>, seconds: Float): Pair<Float, Float> {
            var peak = 0f
            repeat((seconds * fps).toInt().coerceAtLeast(1)) {
                scene.drive(model, pose, dt)
                worstStretch = max(worstStretch, scene.solver.maxStretch())
                // Motion relative to the rig: the goal is where the rig alone would put each vertex.
                for (i in 0 until scene.state.count) peak = max(peak, hypot(scene.state.x[i] - scene.state.goalX[i], scene.state.y[i] - scene.state.goalY[i]))
            }
            var last = 0f
            for (i in 0 until scene.state.count) last = max(last, hypot(scene.state.x[i] - scene.state.goalX[i], scene.state.y[i] - scene.state.goalY[i]))
            return peak to last
        }
        val phases = buildJsonArray {
            for (input in edit.inputs) {
                val parameter = model.parameters.first { it.id.raw == input.parameter }
                val high = mapOf(parameter.id to if (input.reflect) parameter.min else parameter.max)
                scene.reset(model, emptyMap())
                val (heldPeak, _) = runPhase(high, hold)
                val (releasePeak, final) = runPhase(emptyMap(), release)
                add(buildJsonObject {
                    put("input", input.parameter); put("type", if (input.type == PhysicsSourceType.X) "x" else "angle")
                    put("peak_px", round(max(heldPeak, releasePeak))); put("after_release_px", round(final))
                })
            }
            if (wind != null) {
                scene.reset(model, emptyMap())
                scene.solver.settings = scene.solver.settings.copy(windX = wind.first, windY = wind.second)
                val (peak, _) = runPhase(emptyMap(), hold)
                scene.solver.settings = scene.solver.settings.copy(windX = 0f, windY = 0f)
                val (after, final) = runPhase(emptyMap(), release)
                add(buildJsonObject { put("input", "wind"); put("peak_px", round(max(peak, after))); put("after_release_px", round(final)) })
            }
        }
        var restDrift = 0f
        scene.reset(model, emptyMap())
        repeat(fps) { scene.drive(model, emptyMap(), dt) }
        val settled = scene.state.positions()
        for (i in 0 until scene.state.count) restDrift = max(restDrift, hypot(settled[i * 2] - rest[i * 2], settled[i * 2 + 1] - rest[i * 2 + 1]))
        return buildJsonObject {
            put("id", edit.id); put("particles", scene.state.count)
            put("pinned", (0 until scene.state.count).count { scene.solver.pinWeight[it] > 0f })
            put("calibration_residual_px", round(residual)); put("rest_drift_px", round(restDrift))
            put("max_stretch_percent", round(worstStretch * 100f))
            put("phases", phases)
            if (scene.notes.isNotEmpty()) putJsonArray("notes") { scene.notes.forEach { add(it) } }
        }
    }

    /** The simulated vertices of every target of [scene], world space, keyed by mesh. */
    fun positions(scene: SimScene): Map<DrawableId, FloatArray> = scene.offsets.keys.associateWith { requireNotNull(scene.positions(it)) }

    private fun round(value: Float) = kotlin.math.round(value * 100f) / 100f
}
