package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsInput
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reduces a simulation to parameters, keyforms and pendulums (see [SimBakeResult]).
 *
 * 1. Static response: for each static input's end values the rig is posed, the body settles against its
 *    colliders, and what is left against the rig becomes corrections on that parameter's own axis, so the
 *    exported model is pushed exactly as far as the pose asks.
 * 2. Training: the dynamic inputs run steps, holds, sweeps and a mixed stretch; each frame records how far
 *    the body is from the rig (minus the static correction) in each mesh's local keyform space.
 * 3. Subspace: the few principal directions of that residual, measured in px at the default pose.
 * 4. Pendulum: [SimPendulumFit] finds the Cubism pendulum, one segment per mode, whose vertex angles best
 *    explain the motion. Each vertex drives a -1..1 parameter whose keys are the mean residual of the frames
 *    where the pendulum has it near that key, so arcs and one-sided pushes keep their shape.
 *
 * [model] must not carry this simulation's own bake.
 */
object SimBaker {
    class Options(
        val fps: Int = 60,
        val keys: FloatArray = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f),
        /** Scales every phase of the training run; tests shorten it. */
        val duration: Float = 1f,
        val progress: (Float) -> Unit = {},
        val cancelled: () -> Boolean = { false },
    )

    /** Below this much motion (px) at the default pose a mode or static axis is not worth keys. */
    private const val MIN_MOTION_PX = 0.5f
    /** Below this share of the motion's energy a principal direction is left out of the fit. */
    private const val MIN_ENERGY = 0.01f
    /** Principal directions the pendulum fit reads the motion in. */
    private const val SUBSPACE = 6
    /** A key needs this many training frames near it to take their mean shape instead of a straight line. */
    private const val MIN_BIN_FRAMES = 8
    private const val POWER_ITERATIONS = 80
    private const val SETTLE_SECONDS = 2.5f

    fun bake(model: PuppetModel, edit: RigSimEdit, options: Options = Options()): SimBakeResult {
        val scene = SimScene.build(model, edit)
        scene.calibrate(model)
        val space = Space(model, scene)
        val dt = 1f / options.fps
        val parameters = model.parameters.associateBy { it.id.raw }
        fun check() { if (options.cancelled()) throw CancellationException("Bake cancelled") }

        // 1. Static response.
        options.progress(0.02f)
        val statics = staticInputs(model, edit).mapNotNull { raw ->
            check()
            val parameter = parameters[raw] ?: return@mapNotNull null
            val keys = listOf(parameter.min, parameter.default, parameter.max).distinct().sorted().toFloatArray()
            if (keys.size < 2) return@mapNotNull null
            val perKey = keys.map { key -> if (key == parameter.default) FloatArray(space.size) else space.settle(mapOf(parameter.id to key), dt) }
            if (perKey.maxOf { space.motionPx(it) } < MIN_MOTION_PX) return@mapNotNull null
            SimBakedAxis(raw, keys, space.split(perKey))
        }

        // 2. Training run.
        val inputs = edit.inputs.ifEmpty { PhysicsGenerator.headAndBodyInputs(parameters.keys) }
            .filter { input -> parameters[input.parameter]?.let { it.kind == ParameterKind.NORMAL && it.max > it.min } == true }
        require(inputs.isNotEmpty() || statics.isNotEmpty()) { "No input parameter moves ${edit.id}; add inputs to bake it" }
        val track = trajectory(inputs.map { parameters.getValue(it.parameter) }, options.fps, options.duration)
        val frames = track.firstOrNull()?.size ?: 0
        val residuals = ArrayList<FloatArray>(frames)
        if (frames > 0) {
            scene.reset(model, emptyMap())
            for (f in 0 until frames) {
                if (f % 30 == 0) { check(); options.progress(0.05f + 0.7f * f / frames) }
                val pose = HashMap<ParameterId, Float>()
                for ((i, input) in inputs.withIndex()) pose[ParameterId(input.parameter)] = track[i][f]
                scene.drive(model, pose, dt)
                val r = space.residual(pose) ?: FloatArray(space.size)
                for (axis in statics) {
                    val value = pose[ParameterId(axis.parameter)] ?: continue
                    space.subtract(r, axis, value)
                }
                residuals += r
            }
        }

        // 3. The few principal directions (px at the default pose) that span nearly all of the motion.
        options.progress(0.78f)
        val metric = residuals.map(space::toPx)
        val total = metric.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        val directions = ArrayList<FloatArray>()
        for (k in 0 until SUBSPACE) {
            if (metric.isEmpty()) break
            check()
            val v = principal(metric, directions, seed = k)
            val energy = (metric.sumOf { row -> dot(row, v).toDouble().let { it * it } } / total).toFloat()
            if (energy < MIN_ENERGY) break
            directions += v
        }
        val moves = metric.isNotEmpty() && percentile(residuals.map(space::motionPx), 0.98f) >= MIN_MOTION_PX
        require(moves && directions.isNotEmpty() || statics.isNotEmpty()) { "${edit.id} barely moves under its inputs; nothing to bake" }
        if (!moves || directions.isEmpty()) {
            options.progress(1f)
            return SimBakeResult(SimBake.fingerprint(model, edit), space.counts, statics, emptyList())
        }
        val motion = metric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }

        // 4. The pendulum, one segment per mode, and what each of its vertices moves.
        options.progress(0.82f)
        val outputs = (1..edit.modes).map { SimGenerator.parameterId(edit, it) }
        val fit = SimPendulumFit.fit(SimGenerator.physicsId(edit), edit.name, outputs, inputs, PhysicsEngine.ranges(model.parameters),
            track, motion, dt, ::check)
        val shapes = fit.readout.map { readout ->
            val px = FloatArray(space.size)
            for ((d, direction) in directions.withIndex()) for (i in px.indices) px[i] += readout[d] * direction[i]
            space.fromPx(px)
        }
        val kept = outputs.indices.filter { space.motionPx(shapes[it]) >= MIN_MOTION_PX }
        require(kept.isNotEmpty() || statics.isNotEmpty()) { "The fitted pendulum does not move ${edit.id}; nothing to bake" }
        val played = fit.played

        // Key shapes: the mean motion of the frames near each key with the other modes taken out, so arcs
        // and one-sided pushes keep their shape instead of being a straight line.
        options.progress(0.97f)
        val axes = kept.map { k ->
            val u = played[k]
            val perKey = options.keys.map { c ->
                if (c == 0f) return@map FloatArray(space.size)
                val near = u.indices.filter { if (abs(c) >= 1f) u[it] * c >= 0.75f else abs(u[it] - c) <= 0.25f }
                if (near.size < MIN_BIN_FRAMES) return@map FloatArray(space.size) { shapes[k][it] * c }
                val mean = FloatArray(space.size)
                var reach = 0f
                for (f in near) {
                    val r = residuals[f]
                    for (i in mean.indices) {
                        var value = r[i]
                        for (j in kept) if (j != k) value -= played[j][f] * shapes[j][i]
                        mean[i] += value
                    }
                    reach += u[f]
                }
                // The frames sit around the key, not on it: scale the mean to the key itself.
                val scale = c / (reach / near.size)
                for (i in mean.indices) mean[i] = mean[i] / near.size * scale
                mean
            }
            SimBakedAxis(outputs[k], options.keys.copyOf(), space.split(perKey))
        }

        // How well the baked keys, played by the pendulum, reproduce the simulation.
        var missed = 0.0
        val errors = FloatArray(residuals.size) { f ->
            val r = residuals[f].copyOf()
            for ((place, k) in kept.withIndex()) {
                val offsets = space.join(axes[place], played[k][f])
                for (i in r.indices) r[i] -= offsets[i]
            }
            missed += space.toPx(r).sumOf { (it * it).toDouble() }
            space.motionPx(r)
        }
        val modes = kept.mapIndexed { place, k ->
            val reach = space.motionPx(shapes[k])
            val swing = percentile(played[k].map { abs(it) * reach }, 0.98f)
            val energy = (played[k].sumOf { (it * it).toDouble() } * space.toPx(shapes[k]).sumOf { (it * it).toDouble() } / total).toFloat()
            SimBakedMode(axes[place], swing, energy)
        }
        val physics = fit.setting.copy(outputs = fit.setting.outputs.filterIndexed { k, _ -> k in kept })
        options.progress(1f)
        return SimBakeResult(SimBake.fingerprint(model, edit), space.counts, statics, modes, physics,
            (1.0 - missed / total).toFloat(), percentile(errors.toList(), 0.95f))
    }

    /** [edit]'s static inputs, or when it names none, the parameters that move a collider most (at most four). */
    fun staticInputs(model: PuppetModel, edit: RigSimEdit): List<String> {
        edit.staticInputs?.let { return it }
        if (edit.colliders.isEmpty()) return emptyList()
        val evaluator = CpuDeformationEvaluator()
        val colliders = edit.colliders.map { DrawableId(it.drawableId) }
        val rest = evaluator.evaluate(model, emptyMap()).worldPositions
        fun moved(pose: Map<ParameterId, Float>): Float {
            val world = evaluator.evaluate(model, pose).worldPositions
            var most = 0f
            for (id in colliders) {
                val a = rest[id] ?: continue
                val b = world[id] ?: continue
                for (v in 0 until minOf(a.size, b.size) / 2) most = maxOf(most, hypot(a[v * 2] - b[v * 2], a[v * 2 + 1] - b[v * 2 + 1]))
            }
            return most
        }
        return model.parameters.filter { it.kind == ParameterKind.NORMAL && it.max > it.min }
            .map { p -> p.id.raw to maxOf(moved(mapOf(p.id to p.min)), moved(mapOf(p.id to p.max))) }
            .filter { it.second > 2f }.sortedByDescending { it.second }.take(RigSimEdit.MAX_STATIC_INPUTS).map { it.first }
    }

    /**
     * The training run, one array of values per input: for each input a step to its maximum and back, a
     * step to its minimum and back, and a sweep from slow to fast; then all of them together, and rest.
     */
    internal fun trajectory(inputs: List<Parameter>, fps: Int, duration: Float = 1f): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val frames = ArrayList<FloatArray>()
        val here = FloatArray(inputs.size) { 0f }
        fun value(p: Parameter, u: Float) = if (u >= 0f) p.default + u * (p.max - p.default) else p.default + u * (p.default - p.min)
        fun emit() { frames += FloatArray(inputs.size) { value(inputs[it], here[it]) } }
        fun frames(seconds: Float) = (seconds * duration * fps).toInt().coerceAtLeast(1)
        fun ramp(i: Int, to: Float, seconds: Float) {
            val from = here[i]; val n = frames(seconds)
            for (f in 1..n) { val t = f.toFloat() / n; here[i] = from + (to - from) * t * t * (3f - 2f * t); emit() }
        }
        fun hold(seconds: Float) = repeat(frames(seconds)) { emit() }
        for (i in inputs.indices) {
            ramp(i, 1f, 0.2f); hold(1f); ramp(i, 0f, 0.2f); hold(1.4f)
            ramp(i, -1f, 0.2f); hold(1f); ramp(i, 0f, 0.2f); hold(1.4f)
            // A sweep from 0.3 Hz to 1.5 Hz at half the range: faster, fuller sweeps only excite resonance a head never drives.
            val n = frames(5f); var phase = 0.0
            for (f in 0 until n) {
                val hz = 0.3 + 1.2 * f / n
                phase += 2 * PI * hz / fps / duration
                here[i] = (0.5 * sin(phase)).toFloat() * minOf(1f, f / (fps * 0.3f * duration))
                emit()
            }
            ramp(i, 0f, 0.3f); hold(1f)
        }
        // Everything at once: a few incommensurate sines per input.
        val n = frames(8f)
        val random = java.util.Random(7L)
        val waves = inputs.indices.map { List(3) { Triple(0.3 + random.nextDouble() * 1.5, random.nextDouble() * 2 * PI, 0.2 + random.nextDouble() * 0.2) } }
        for (f in 0 until n) {
            val t = f.toDouble() / fps / duration
            val fade = minOf(1f, f / (fps * 0.5f * duration), (n - f) / (fps * 0.5f * duration))
            for (i in inputs.indices) here[i] = (waves[i].sumOf { (hz, phase, a) -> a * sin(2 * PI * hz * t + phase) }.toFloat() * fade).coerceIn(-1f, 1f)
            emit()
        }
        for (i in inputs.indices) here[i] = 0f
        hold(2f)
        return inputs.indices.map { i -> FloatArray(frames.size) { frames[it][i] } }
    }

    /**
     * The targets' keyform spaces: residuals in local coordinates, and the default-pose Jacobian that
     * turns them into px so modes are measured the same way everywhere on the body.
     */
    private class Space(val model: PuppetModel, val scene: SimScene) {
        val meshes: List<DrawableId> = scene.offsets.keys.toList()
        val size = scene.state.count * 2
        val counts: Map<String, Int> = meshes.associate { it.raw to scene.vertexCounts.getValue(it) }
        /** Per particle: d world / d local at the default pose, column-major (xx, yx, xy, yy). */
        private val jacobian = FloatArray(scene.state.count * 4)
        private val inverse = FloatArray(scene.state.count * 4)

        init {
            for (id in meshes) {
                val offset = scene.offsets.getValue(id)
                val mapping = drawableSpaceMapping(model, emptyMap(), id)
                val local = drawableLocalPosed(model, emptyMap(), id)
                val count = scene.vertexCounts.getValue(id)
                for (v in 0 until count) { val i = (offset + v) * 4; jacobian[i] = 1f; jacobian[i + 3] = 1f }
                if (mapping == null || local == null) continue
                var extent = 0f
                for (k in local.indices step 2) extent = maxOf(extent, abs(local[k] - local[0]), abs(local[k + 1] - local[1]))
                val eps = (extent * 1e-3f).coerceAtLeast(1e-6f)
                val base = mapping.localToWorld(local)
                val dx = mapping.localToWorld(FloatArray(local.size) { if (it % 2 == 0) local[it] + eps else local[it] })
                val dy = mapping.localToWorld(FloatArray(local.size) { if (it % 2 == 1) local[it] + eps else local[it] })
                for (v in 0 until count) {
                    val i = (offset + v) * 4
                    jacobian[i] = (dx[v * 2] - base[v * 2]) / eps; jacobian[i + 1] = (dx[v * 2 + 1] - base[v * 2 + 1]) / eps
                    jacobian[i + 2] = (dy[v * 2] - base[v * 2]) / eps; jacobian[i + 3] = (dy[v * 2 + 1] - base[v * 2 + 1]) / eps
                }
            }
            for (p in 0 until scene.state.count) {
                val i = p * 4
                val det = jacobian[i] * jacobian[i + 3] - jacobian[i + 2] * jacobian[i + 1]
                if (abs(det) < 1e-12f) { inverse[i] = 1f; inverse[i + 3] = 1f; continue }
                inverse[i] = jacobian[i + 3] / det; inverse[i + 1] = -jacobian[i + 1] / det
                inverse[i + 2] = -jacobian[i + 2] / det; inverse[i + 3] = jacobian[i] / det
            }
        }

        /** Local deltas in px at the default pose. */
        fun toPx(local: FloatArray) = FloatArray(size) { k ->
            val p = k / 2; val i = p * 4; val x = local[p * 2]; val y = local[p * 2 + 1]
            if (k % 2 == 0) jacobian[i] * x + jacobian[i + 2] * y else jacobian[i + 1] * x + jacobian[i + 3] * y
        }

        fun fromPx(px: FloatArray) = FloatArray(size) { k ->
            val p = k / 2; val i = p * 4; val x = px[p * 2]; val y = px[p * 2 + 1]
            if (k % 2 == 0) inverse[i] * x + inverse[i + 2] * y else inverse[i + 1] * x + inverse[i + 3] * y
        }

        /** The largest single-vertex distance of [local] in px. */
        fun motionPx(local: FloatArray): Float {
            val px = toPx(local)
            var most = 0f
            for (p in 0 until size / 2) most = maxOf(most, hypot(px[p * 2], px[p * 2 + 1]))
            return most
        }

        /** Where the simulation has the body at [pose], minus where the rig has it, in local coordinates. */
        fun residual(pose: Map<ParameterId, Float>): FloatArray? {
            val out = FloatArray(size)
            val s = scene.state
            for (id in meshes) {
                val offset = scene.offsets.getValue(id)
                val count = scene.vertexCounts.getValue(id)
                val mapping = drawableSpaceMapping(model, pose, id) ?: return null
                val seed = drawableLocalPosed(model, pose, id) ?: return null
                val all = (0 until count).toSet()
                val simulated = FloatArray(count * 2) { if (it % 2 == 0) s.x[offset + it / 2] else s.y[offset + it / 2] }
                val rigged = FloatArray(count * 2) { if (it % 2 == 0) s.goalX[offset + it / 2] else s.goalY[offset + it / 2] }
                val a = mapping.worldToLocal(simulated, seed, all)
                val b = mapping.worldToLocal(rigged, seed, all)
                for (k in 0 until count * 2) out[offset * 2 + k] = a[k] - b[k]
            }
            return out
        }

        /** Poses the rig, lets the body settle there and returns what is left against the rig. */
        fun settle(pose: Map<ParameterId, Float>, dt: Float): FloatArray {
            val s = scene.state
            val calm = s.damping.copyOf()
            try {
                for (i in 0 until s.count) s.damping[i] = maxOf(calm[i], 8f)
                scene.reset(model, pose)
                repeat((SETTLE_SECONDS / dt).toInt()) { scene.drive(model, pose, dt) }
                return residual(pose) ?: FloatArray(size)
            } finally {
                calm.copyInto(s.damping)
            }
        }

        /** [r] minus [axis]'s offsets at [value]. */
        fun subtract(r: FloatArray, axis: SimBakedAxis, value: Float) {
            for (id in meshes) {
                val offsets = axis.at(id.raw, value) ?: continue
                val offset = scene.offsets.getValue(id) * 2
                for (k in offsets.indices) r[offset + k] -= offsets[k]
            }
        }

        /** Per-key arrays over every particle split into per-mesh arrays. */
        fun split(perKey: List<FloatArray>): Map<String, List<FloatArray>> = meshes.associate { id ->
            val offset = scene.offsets.getValue(id) * 2
            val count = scene.vertexCounts.getValue(id) * 2
            id.raw to perKey.map { it.copyOfRange(offset, offset + count) }
        }

        /** [axis]'s offsets at [value] over every particle. */
        fun join(axis: SimBakedAxis, value: Float): FloatArray {
            val out = FloatArray(size)
            for (id in meshes) axis.at(id.raw, value)?.copyInto(out, scene.offsets.getValue(id) * 2)
            return out
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** The unit direction of most energy in [rows], orthogonal to [found] (power iteration on RᵀR). */
    private fun principal(rows: List<FloatArray>, found: List<FloatArray>, seed: Int): FloatArray {
        val size = rows.first().size
        val random = java.util.Random(31L + seed)
        var v = FloatArray(size) { random.nextFloat() - 0.5f }
        repeat(POWER_ITERATIONS) {
            for (u in found) { val d = dot(v, u); for (i in v.indices) v[i] -= d * u[i] }
            val next = FloatArray(size)
            for (row in rows) { val d = dot(row, v); if (d != 0f) for (i in next.indices) next[i] += d * row[i] }
            for (u in found) { val d = dot(next, u); for (i in next.indices) next[i] -= d * u[i] }
            val length = sqrt(dot(next, next))
            if (length < 1e-20f) return v.also { normalize(it) }
            for (i in next.indices) next[i] /= length
            v = next
        }
        return v
    }

    private fun normalize(v: FloatArray) {
        val length = sqrt(dot(v, v))
        if (length > 0f) for (i in v.indices) v[i] /= length
    }

    private fun percentile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
    }
}
