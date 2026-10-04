package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import kotlin.math.abs

/**
 * A scripted run of the exported physics for the `physics` tool: the model rests at its defaults, the
 * given inputs jump to their values and hold, then drop back, and every output is traced through both.
 */
object PhysicsSimulation {
    private const val FPS = 60

    fun run(groups: List<PhysicsGroup>, parameters: List<Parameter>, arguments: JsonObject, physicsEnabled: Boolean,
        physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): JsonObject {
        fun check() { if (cancelled()) throw java.util.concurrent.CancellationException("Physics sampling cancelled") }
        check(); progress(0f)
        val ids = arguments["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        val chosen = groups.filter { if (ids == null) it.active else it.id in ids }
        ids?.firstOrNull { id -> groups.none { it.id == id } }?.let { throw IllegalArgumentException("Physics group not found: $it") }
        require(chosen.isNotEmpty()) { "No active physics group to simulate" }
        val ranges = PhysicsEngine.ranges(parameters)
        val inputs = arguments["inputs"]?.jsonObject.orEmpty().mapValues { (id, v) ->
            val range = ranges[id] ?: throw IllegalArgumentException("Parameter $id does not exist")
            v.jsonPrimitive.float.coerceIn(range.min, range.max)
        }
        require(inputs.isNotEmpty()) { "inputs needs at least one parameter value" }
        val duration = (arguments["duration"]?.jsonPrimitive?.floatOrNull ?: 3f).coerceIn(0.1f, 20f)
        val hold = (arguments["hold"]?.jsonPrimitive?.floatOrNull ?: 1f).coerceIn(0f, duration)
        val samples = (arguments["samples"]?.jsonPrimitive?.intOrNull ?: 12).coerceIn(2, 60)

        val engine = PhysicsEngine(chosen.map { it.setting }, ranges, physicsFps.toFloat())
        val rest = parameters.associate { it.id.raw to it.default }
        val frames = (duration * FPS).toInt()
        val traces = LinkedHashMap<String, FloatArray>()
        for (group in chosen) for (o in group.setting.outputs) traces.getOrPut(o.parameter) { FloatArray(frames + 1) }
        engine.settle(rest, progress = { progress(0.1f * it) }, cancelled = cancelled).forEach { (p, v) -> traces[p]?.set(0, v) }
        for (f in 1..frames) {
            check()
            val t = f.toFloat() / FPS
            val values = if (t <= hold) rest + inputs else rest
            engine.step(values, 1f / FPS).forEach { (p, v) -> traces[p]?.set(f, v) }
            progress(0.1f + 0.9f * f / frames)
        }
        check()
        return buildJsonObject {
            if (!physicsEnabled) put("note", "Physics is switched off in settings; the model exports none of these groups")
            put("fps", physicsFps); put("hold", hold); put("duration", duration)
            putJsonArray("outputs") {
                for ((parameter, trace) in traces) add(buildJsonObject {
                    put("parameter", parameter)
                    put("group", chosen.first { g -> g.setting.outputs.any { it.parameter == parameter } }.id)
                    val span = ranges.getValue(parameter).let { it.max - it.min }.coerceAtLeast(1e-6f)
                    put("while_held", phase(trace, 0, (hold * FPS).toInt(), span))
                    put("after_release", phase(trace, (hold * FPS).toInt(), frames, span))
                    putJsonArray("samples") {
                        for (k in 0 until samples) {
                            val f = k * frames / (samples - 1)
                            add(buildJsonArray { add(round(f.toFloat() / FPS)); add(round(trace[f])) })
                        }
                    }
                })
            }
        }
    }

    /** Peak, final value and when the trace last left 2% of the range around it, for frames [from]..[to]. */
    private fun phase(trace: FloatArray, from: Int, to: Int, span: Float) = buildJsonObject {
        if (to <= from) return@buildJsonObject
        val final = trace[to]
        val peak = (from..to).maxBy { abs(trace[it] - trace[from]) }
        val unsettled = (from..to).lastOrNull { abs(trace[it] - final) > span * 0.02f }
        put("start", round(trace[from])); put("peak", round(trace[peak])); put("peak_time", round(peak.toFloat() / FPS))
        put("final", round(final))
        put("settle_time", if (unsettled == null) round(from.toFloat() / FPS) else round((unsettled + 1).toFloat() / FPS))
        // Quiet for the last quarter second of the phase.
        put("settled", unsettled == null || unsettled < to - FPS / 4)
    }

    private fun round(v: Float) = kotlin.math.round(v * 1000f) / 1000f
}

/**
 * How a group answers a standard drag: the target jumps fully right, holds for [hold] seconds and is let go,
 * followed through the same drag and physics as the panel's pendulum. Values are fractions of each output
 * parameter's half range.
 */
object PhysicsResponse {
    data class Trace(
        val times: FloatArray,
        /** The drag's followed point, -1..1. */
        val drag: FloatArray,
        val outputs: Map<String, FloatArray>,
        val hold: Float,
        /** Per output index, how far its unclamped swing reached toward the parameter's end (1 = exactly). */
        val reach: Map<Int, Float> = emptyMap(),
    ) {
        /** When [parameter] last left 5% of its half range around its final value, in seconds. */
        fun settleTime(parameter: String): Float {
            val trace = outputs[parameter] ?: return 0f
            val last = trace.indices.lastOrNull { abs(trace[it] - trace.last()) > 0.05f } ?: return 0f
            return times[(last + 1).coerceAtMost(times.size - 1)]
        }

        fun peak(parameter: String): Float = outputs[parameter]?.maxOfOrNull { abs(it) } ?: 0f
    }

    fun trace(setting: RigPhysicsEdit, ranges: Map<String, PhysicsEngine.Range>, physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS,
        hold: Float = 1f, duration: Float = 3.5f, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Trace {
        fun check() { if (cancelled()) throw java.util.concurrent.CancellationException("Physics fitting cancelled") }
        check(); progress(0f)
        val fps = 60
        val frames = (duration * fps).toInt()
        val engine = PhysicsEngine(listOf(setting), ranges, physicsFps.toFloat())
        val drag = PhysicsDrag()
        val inputs = setting.inputs.map { it.parameter }
        val outputs = setting.outputs.map { it.parameter }.filter { it in ranges }.associateWith { FloatArray(frames) }
        val times = FloatArray(frames)
        val dragTrace = FloatArray(frames)
        engine.settle(emptyMap(), progress = { progress(0.1f * it) }, cancelled = cancelled)
        engine.strands.forEach { it.resetPeaks() }
        for (f in 0 until frames) {
            check()
            val t = f.toFloat() / fps
            if (t < hold) drag.target(1f, 0f) else drag.release()
            drag.update(1f / fps)
            val out = engine.step(drag.apply(emptyMap(), inputs, ranges), 1f / fps)
            times[f] = t
            dragTrace[f] = drag.x
            for ((p, trace) in outputs) {
                val r = ranges.getValue(p)
                val half = maxOf(abs(r.max - r.default), abs(r.min - r.default)).coerceAtLeast(1e-6f)
                trace[f] = ((out[p] ?: r.default) - r.default) / half
            }
            progress(0.1f + 0.9f * (f + 1) / frames)
        }
        check()
        val strand = engine.strands.single()
        val reach = setting.outputs.indices.filter { setting.outputs[it].parameter in ranges }
            .associateWith { strand.peakFraction(it, ranges.getValue(setting.outputs[it].parameter)) }
        return Trace(times, dragTrace, outputs, hold, reach)
    }
}
