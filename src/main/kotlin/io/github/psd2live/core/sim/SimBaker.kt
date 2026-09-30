package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.RigEditOverlay
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
 * 1. Static response: for each static input's keys the rig is posed, the body settles against its
 *    colliders, and what is left against the rig becomes corrections on that parameter's own axis, so the
 *    exported model is pushed exactly as far as the pose asks.
 * 2. Training: the dynamic inputs run steps, sweeps and a mixed stretch, each piece from rest and all of
 *    them at once on separate copies of the body; each frame records how far the body is from the rig
 *    (minus the static correction) in each mesh's local keyform space.
 * 3. Subspace: the few principal directions of that residual, measured in px at the default pose.
 * 4. Pendulum: [SimPendulumFit] finds the Cubism pendulum whose driven vertices' angles best explain the
 *    motion, each vertex driving one -1..1 parameter.
 * 5. Keys: the key shapes of all mode parameters are solved together, by least squares over every frame
 *    as the pendulum plays it, so what is exported is the closest those keys can come to the simulation -
 *    arcs and one-sided pushes included.
 *
 * [model] must not carry this simulation's own bake.
 */
object SimBaker {
    class Options(
        val fps: Int = 60,
        /** The rate the exported pendulum steps at (physics3.json `Fps`); [RigEditOverlay.UNLIMITED_FPS] steps once per frame. */
        val physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS,
        /** Scales every phase of the training run; tests shorten it. */
        val duration: Float = 1f,
        /** Runs the pieces of the training on several cores. */
        val parallel: Boolean = true,
        /** Pendulum segments above the driven ones: they add lag without adding parameters. */
        val extraSegments: Int = 0,
        /** The pendulum of the bake being replaced: the fit starts from it and searches close by, which is quicker. */
        val previous: io.github.psd2live.core.RigPhysicsEdit? = null,
        val progress: (Float) -> Unit = {},
        val cancelled: () -> Boolean = { false },
    )

    /** Below this much motion (px) at the default pose a mode or static axis is not worth keys. */
    private const val MIN_MOTION_PX = 0.5f
    /** Below this share of the motion's energy a principal direction is left out of the fit. */
    private const val MIN_ENERGY = 0.01f
    /** Principal directions the pendulum fit reads the motion in. */
    private const val SUBSPACE = 6
    /** How strongly key shapes with few frames near them lean toward a straight line through the mode. */
    private const val KEY_RIDGE = 1e-3
    private const val SETTLE_SECONDS = 2.5f

    fun bake(model: PuppetModel, edit: RigSimEdit, options: Options = Options()): SimBakeResult {
        val calibrated = SimScene.build(model, edit)
        calibrated.calibrate(model)
        val space = Space(model, calibrated)
        val dt = 1f / options.fps
        val parameters = model.parameters.associateBy { it.id.raw }
        fun check() { if (options.cancelled()) throw CancellationException("Bake cancelled") }
        /** A copy of the calibrated body for one piece of work. */
        fun body() = SimScene.build(model, edit).also { it.adopt(calibrated) }
        fun <T, R> each(items: List<T>, work: (T) -> R): List<R> =
            if (options.parallel && items.size > 1) items.parallelStream().map(work).toList() else items.map(work)

        // 1. Static response, every key of every static input settled on its own body.
        options.progress(0.02f)
        val staticAxes = staticInputs(model, edit).mapNotNull { raw ->
            val parameter = parameters[raw]?.takeIf { it.kind == ParameterKind.NORMAL } ?: return@mapNotNull null
            staticKeys(parameter, edit.keys).takeIf { it.size >= 2 }?.let { parameter to it }
        }
        val settled = each(staticAxes.flatMap { (parameter, keys) -> keys.map { parameter to it } }) { (parameter, key) ->
            check()
            if (key == parameter.default) FloatArray(space.size) else space.settle(body(), mapOf(parameter.id to key), dt)
        }
        var cursor = 0
        val statics = staticAxes.mapNotNull { (parameter, keys) ->
            val perKey = keys.map { settled[cursor++] }
            if (perKey.maxOf { space.motionPx(it) } < MIN_MOTION_PX) null else SimBakedAxis(parameter.id.raw, keys, space.split(perKey))
        }

        // 2. Training run: each piece from rest on its own body.
        options.progress(0.1f)
        val inputs = edit.inputs.map { it.parameter }.ifEmpty { PhysicsGenerator.headAndBodyInputs(parameters.keys).map { it.parameter } }
            .filter { input -> parameters[input]?.let { it.kind == ParameterKind.NORMAL && it.max > it.min } == true }
        require(inputs.isNotEmpty() || statics.isNotEmpty()) { "No input parameter moves ${edit.id}; add inputs to bake it" }
        val pieces = trajectory(inputs.map { parameters.getValue(it) }, options.fps, options.duration)
        val heldOutPiece = heldOut(inputs.map { parameters.getValue(it) }, options.fps, options.duration)
        val all = each(pieces + listOf(heldOutPiece)) { piece ->
            val scene = body()
            scene.reset(model, emptyMap())
            val frames = piece.firstOrNull()?.size ?: 0
            List(frames) { f ->
                if (f % 30 == 0) check()
                val pose = HashMap<ParameterId, Float>()
                for ((i, input) in inputs.withIndex()) pose[ParameterId(input)] = piece[i][f]
                scene.drive(model, pose, dt)
                val r = space.residual(scene, pose) ?: FloatArray(space.size)
                for (axis in statics) pose[ParameterId(axis.parameter)]?.let { space.subtract(r, axis, it) }
                r
            }
        }
        val recorded = all.dropLast(1)
        val residuals = recorded.flatten()
        val heldOutResiduals = all.last()
        val track = inputs.indices.map { i -> pieces.flatMap { it[i].asList() }.toFloatArray() }
        val starts = HashSet<Int>().also { set -> var at = 0; for (piece in recorded) { set += at; at += piece.size } }

        // 3. The few principal directions (px at the default pose) that span nearly all of the motion.
        options.progress(0.6f)
        check()
        val metric = residuals.map(space::toPx)
        val total = metric.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        // Every other frame is plenty to find them: the motion is smooth at the frame rate.
        val sampled = metric.filterIndexed { f, _ -> f % 2 == 0 }
        val sampledTotal = sampled.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        val directions = if (sampled.isEmpty()) emptyList() else principal(sampled, SUBSPACE).filter { it.second / sampledTotal >= MIN_ENERGY }.map { it.first }
        val moves = metric.isNotEmpty() && percentile(residuals.map(space::motionPx), 0.98f) >= MIN_MOTION_PX
        require(moves && directions.isNotEmpty() || statics.isNotEmpty()) { "${edit.id} barely moves under its inputs; nothing to bake" }
        val fingerprint = SimBake.fingerprint(model, edit)
        if (!moves || directions.isEmpty()) {
            options.progress(1f)
            return SimBakeResult(fingerprint, space.counts, statics, emptyList())
        }
        val motion = metric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }
        val heldOutMetric = heldOutResiduals.map(space::toPx)
        val heldOutMotion = heldOutMetric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }

        // 4. The pendulum.
        options.progress(0.65f)
        val outputs = (1..edit.modes).map { SimGenerator.parameterId(edit, it) }
        val fit = SimPendulumFit.fit(SimGenerator.physicsId(edit), edit.name, outputs, inputs, PhysicsEngine.ranges(model.parameters),
            track, motion, dt, options.physicsFps.toFloat(), segments = edit.modes + options.extraSegments, starts = starts,
            heldOutTrack = heldOutPiece, heldOutMotion = heldOutMotion, previous = options.previous, check = ::check)

        // 5. Key shapes for every mode at once; a mode that ends up moving nothing is dropped and the rest solved again.
        options.progress(0.95f)
        val keys = edit.modeKeys
        var kept = outputs.indices.toList()
        var shapes = solveKeys(residuals, kept.map { fit.played[it] }, keys, space.size)
        while (kept.isNotEmpty()) {
            val moving = kept.filterIndexed { place, _ -> shapes[place].maxOf { space.motionPx(it) } >= MIN_MOTION_PX }
            if (moving.size == kept.size) break
            kept = moving
            shapes = if (kept.isEmpty()) emptyList() else solveKeys(residuals, kept.map { fit.played[it] }, keys, space.size)
        }
        require(kept.isNotEmpty() || statics.isNotEmpty()) { "The fitted pendulum does not move ${edit.id}; nothing to bake" }
        val axes = kept.mapIndexed { place, k -> SimBakedAxis(outputs[k], keys.copyOf(), space.split(shapes[place])) }

        // What each mode moves over the training.
        val contributions = DoubleArray(kept.size)
        val swings = kept.map { ArrayList<Float>(residuals.size) }
        for (f in residuals.indices) for ((place, k) in kept.withIndex()) {
            val px = space.toPx(space.join(axes[place], fit.played[k][f]))
            contributions[place] += px.sumOf { (it * it).toDouble() }
            swings[place] += maxDistance(px)
        }
        val modes = kept.indices.map { place -> SimBakedMode(axes[place], percentile(swings[place], 0.98f), (contributions[place] / total).toFloat()) }

        // How well the baked keys, played by the pendulum, reproduce the simulation on the motion the fit never saw.
        val judged = heldOutMetric.sumOf { row -> row.sumOf { (it * it).toDouble() } } > total * 1e-3
        val (checkResiduals, checkPlayed) = if (judged) heldOutResiduals to fit.heldOut else residuals to fit.played
        val checkTotal = if (judged) heldOutMetric.sumOf { row -> row.sumOf { (it * it).toDouble() } } else total
        var missed = 0.0
        val errors = FloatArray(checkResiduals.size) { f ->
            val r = checkResiduals[f].copyOf()
            for ((place, k) in kept.withIndex()) {
                val offsets = space.join(axes[place], checkPlayed[k][f])
                for (i in r.indices) r[i] -= offsets[i]
            }
            val px = space.toPx(r)
            missed += px.sumOf { (it * it).toDouble() }
            maxDistance(px)
        }
        val physics = fit.setting.copy(outputs = fit.setting.outputs.filterIndexed { k, _ -> k in kept })
        options.progress(1f)
        return SimBakeResult(fingerprint, space.counts, statics, modes, physics,
            (1.0 - missed / checkTotal).toFloat(), percentile(errors.toList(), 0.95f))
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

    /** [count] keys over [parameter]'s range, half on each side of its default (the default itself is one). */
    internal fun staticKeys(parameter: Parameter, count: Int): FloatArray {
        val side = (count - 1) / 2
        val below = if (parameter.default > parameter.min) List(side) { parameter.min + (parameter.default - parameter.min) * it / side } else emptyList()
        val above = if (parameter.max > parameter.default) List(side) { parameter.max - (parameter.max - parameter.default) * it / side }.reversed() else emptyList()
        return (below + parameter.default + above).distinct().toFloatArray()
    }

    /**
     * The training run as pieces that each start and end at rest, each piece one array of values per input:
     * for each input a step to its maximum and back and to its minimum and back, then a sweep from slow to
     * fast; then all of them together.
     */
    internal fun trajectory(inputs: List<Parameter>, fps: Int, duration: Float = 1f): List<List<FloatArray>> {
        if (inputs.isEmpty()) return emptyList()
        fun value(p: Parameter, u: Float) = if (u >= 0f) p.default + u * (p.max - p.default) else p.default + u * (p.default - p.min)
        fun frames(seconds: Float) = (seconds * duration * fps).toInt().coerceAtLeast(1)
        fun piece(build: (here: FloatArray, emit: () -> Unit) -> Unit): List<FloatArray> {
            val frames = ArrayList<FloatArray>()
            val here = FloatArray(inputs.size)
            build(here) { frames += FloatArray(inputs.size) { value(inputs[it], here[it]) } }
            return inputs.indices.map { i -> FloatArray(frames.size) { frames[it][i] } }
        }
        fun ramp(here: FloatArray, emit: () -> Unit, i: Int, to: Float, seconds: Float) {
            val from = here[i]; val n = frames(seconds)
            for (f in 1..n) { val t = f.toFloat() / n; here[i] = from + (to - from) * t * t * (3f - 2f * t); emit() }
        }
        fun hold(emit: () -> Unit, seconds: Float) = repeat(frames(seconds)) { emit() }
        val pieces = ArrayList<List<FloatArray>>()
        for (i in inputs.indices) {
            pieces += piece { here, emit ->
                ramp(here, emit, i, 1f, 0.2f); hold(emit, 1.1f); ramp(here, emit, i, 0f, 0.2f); hold(emit, 1.1f)
                ramp(here, emit, i, -1f, 0.2f); hold(emit, 1.1f); ramp(here, emit, i, 0f, 0.2f); hold(emit, 1.1f)
            }
            // A sweep from 0.3 Hz to 1.5 Hz at half the range: faster, fuller sweeps only excite resonance a head never drives.
            pieces += piece { here, emit ->
                val n = frames(4f); var phase = 0.0
                for (f in 0 until n) {
                    val hz = 0.3 + 1.2 * f / n
                    phase += 2 * PI * hz / fps / duration
                    here[i] = (0.5 * sin(phase)).toFloat() * minOf(1f, f / (fps * 0.3f * duration))
                    emit()
                }
                ramp(here, emit, i, 0f, 0.3f); hold(emit, 1f)
            }
        }
        // Everything at once: a few incommensurate sines per input.
        pieces += piece { here, emit ->
            val n = frames(6f)
            val random = java.util.Random(7L)
            val waves = inputs.indices.map { List(3) { Triple(0.3 + random.nextDouble() * 1.5, random.nextDouble() * 2 * PI, 0.2 + random.nextDouble() * 0.2) } }
            for (f in 0 until n) {
                val t = f.toDouble() / fps / duration
                val fade = minOf(1f, f / (fps * 0.5f * duration), (n - f) / (fps * 0.5f * duration))
                for (i in inputs.indices) here[i] = (waves[i].sumOf { (hz, phase, a) -> a * sin(2 * PI * hz * t + phase) }.toFloat() * fade).coerceIn(-1f, 1f)
                emit()
            }
            here.fill(0f)
            hold(emit, 1.5f)
        }
        return pieces
    }

    /** A stretch of mixed motion unlike the training's, from rest to rest, for judging the fit. */
    internal fun heldOut(inputs: List<Parameter>, fps: Int, duration: Float = 1f): List<FloatArray> {
        val n = (5f * duration * fps).toInt().coerceAtLeast(1)
        val hold = (1f * duration * fps).toInt().coerceAtLeast(1)
        val random = java.util.Random(11L)
        val waves = inputs.indices.map { List(3) { Triple(0.25 + random.nextDouble() * 1.4, random.nextDouble() * 2 * PI, 0.15 + random.nextDouble() * 0.25) } }
        return inputs.mapIndexed { i, p ->
            FloatArray(n + hold) { f ->
                if (f >= n) return@FloatArray p.default
                val t = f.toDouble() / fps / duration
                val fade = minOf(1f, f / (fps * 0.4f * duration), (n - f) / (fps * 0.4f * duration))
                val u = (waves[i].sumOf { (hz, phase, a) -> a * sin(2 * PI * hz * t + phase) }.toFloat() * fade).coerceIn(-1f, 1f)
                if (u >= 0f) p.default + u * (p.max - p.default) else p.default + u * (p.default - p.min)
            }
        }
    }

    /**
     * Key shapes for modes played as [played] (per mode, -1..1 per frame), all modes solved together by least
     * squares over [residuals]: each frame is the sum of every mode's keys interpolated at its value. A key
     * few frames reach leans toward the mode's straight-line shape. Returns per mode, per key, the offsets.
     */
    internal fun solveKeys(residuals: List<FloatArray>, played: List<FloatArray>, keys: FloatArray, size: Int): List<List<FloatArray>> {
        val n = played.size
        val zero = keys.indexOfFirst { it == 0f }
        val free = keys.indices.filter { it != zero }
        val unknowns = n * free.size
        fun column(k: Int, key: Int) = k * free.size + free.indexOf(key)
        // The straight-line shapes: one shape per mode scaled by its value.
        val lineA = Array(n) { DoubleArray(n) }
        val lineB = Array(n) { DoubleArray(size) }
        val a = Array(unknowns) { DoubleArray(unknowns) }
        val b = Array(unknowns) { DoubleArray(size) }
        val weights = IntArray(2 * n); val values = DoubleArray(2 * n)
        for ((f, r) in residuals.withIndex()) {
            var active = 0
            for (k in 0 until n) {
                val u = played[k][f].coerceIn(keys.first(), keys.last())
                var j = 0
                while (j < keys.size - 2 && u > keys[j + 1]) j++
                val t = ((u - keys[j]) / (keys[j + 1] - keys[j])).toDouble()
                if (j != zero && t < 1.0) { weights[active] = column(k, j); values[active++] = 1.0 - t }
                if (j + 1 != zero && t > 0.0) { weights[active] = column(k, j + 1); values[active++] = t }
                for (l in 0 until n) lineA[k][l] += u.toDouble() * played[l][f].coerceIn(-1f, 1f)
                val bk = lineB[k]
                for (i in 0 until size) bk[i] += u * r[i]
            }
            for (p in 0 until active) {
                for (q in 0 until active) a[weights[p]][weights[q]] += values[p] * values[q]
                val bp = b[weights[p]]; val v = values[p]
                for (i in 0 until size) bp[i] += v * r[i]
            }
        }
        val lineScale = (0 until n).maxOf { lineA[it][it] }.coerceAtLeast(1e-12)
        for (k in 0 until n) lineA[k][k] += 1e-2 * lineScale
        val line = SimPendulumFit.solve(lineA, lineB) ?: Array(n) { DoubleArray(size) }
        val scale = (0 until unknowns).maxOf { a[it][it] }.coerceAtLeast(1e-12)
        val ridge = KEY_RIDGE * scale
        for (k in 0 until n) for (key in free) {
            val c = column(k, key)
            a[c][c] += ridge
            for (i in 0 until size) b[c][i] += ridge * keys[key] * line[k][i]
        }
        val solved = SimPendulumFit.solve(a, b) ?: Array(unknowns) { c -> DoubleArray(size) { i -> keys[free[c % free.size]] * line[c / free.size][i] } }
        return List(n) { k -> keys.indices.map { key -> if (key == zero) FloatArray(size) else FloatArray(size) { solved[column(k, key)][it].toFloat() } } }
    }

    /**
     * The targets' keyform spaces: residuals in local coordinates, and the default-pose Jacobian that
     * turns them into px so modes are measured the same way everywhere on the body.
     */
    private class Space(val model: PuppetModel, scene: SimScene) {
        val offsets: Map<DrawableId, Int> = scene.offsets
        val vertexCounts: Map<DrawableId, Int> = scene.vertexCounts
        val meshes: List<DrawableId> = offsets.keys.toList()
        val size = scene.state.count * 2
        val counts: Map<String, Int> = meshes.associate { it.raw to vertexCounts.getValue(it) }
        /** Per particle: d world / d local at the default pose, column-major (xx, yx, xy, yy). */
        private val jacobian = FloatArray(scene.state.count * 4)
        private val inverse = FloatArray(scene.state.count * 4)

        init {
            for (id in meshes) {
                val offset = offsets.getValue(id)
                val mapping = drawableSpaceMapping(model, emptyMap(), id)
                val local = drawableLocalPosed(model, emptyMap(), id)
                val count = vertexCounts.getValue(id)
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

        /** The largest single-vertex distance of [local] in px. */
        fun motionPx(local: FloatArray): Float = maxDistance(toPx(local))

        /** Where [scene] has the body at [pose], minus where the rig has it, in local coordinates. */
        fun residual(scene: SimScene, pose: Map<ParameterId, Float>): FloatArray? {
            val out = FloatArray(size)
            val s = scene.state
            for (id in meshes) {
                val offset = offsets.getValue(id)
                val count = vertexCounts.getValue(id)
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

        /** Poses the rig, lets [scene] settle there and returns what is left against the rig. */
        fun settle(scene: SimScene, pose: Map<ParameterId, Float>, dt: Float): FloatArray {
            val s = scene.state
            for (i in 0 until s.count) s.damping[i] = maxOf(s.damping[i], 8f)
            scene.reset(model, pose)
            repeat((SETTLE_SECONDS / dt).toInt()) { scene.drive(model, pose, dt) }
            return residual(scene, pose) ?: FloatArray(size)
        }

        /** [r] minus [axis]'s offsets at [value]. */
        fun subtract(r: FloatArray, axis: SimBakedAxis, value: Float) {
            for (id in meshes) {
                val offsets = axis.at(id.raw, value) ?: continue
                val offset = this.offsets.getValue(id) * 2
                for (k in offsets.indices) r[offset + k] -= offsets[k]
            }
        }

        /** Per-key arrays over every particle split into per-mesh arrays. */
        fun split(perKey: List<FloatArray>): Map<String, List<FloatArray>> = meshes.associate { id ->
            val offset = offsets.getValue(id) * 2
            val count = vertexCounts.getValue(id) * 2
            id.raw to perKey.map { it.copyOfRange(offset, offset + count) }
        }

        /** [axis]'s offsets at [value] over every particle. */
        fun join(axis: SimBakedAxis, value: Float): FloatArray {
            val out = FloatArray(size)
            for (id in meshes) axis.at(id.raw, value)?.copyInto(out, offsets.getValue(id) * 2)
            return out
        }
    }

    private fun maxDistance(px: FloatArray): Float {
        var most = 0f
        for (p in 0 until px.size / 2) most = maxOf(most, hypot(px[p * 2], px[p * 2 + 1]))
        return most
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * Up to [count] unit directions of most energy in [rows] with the energy along each, largest first:
     * a randomized subspace iteration over a slightly larger block, then its exact eigenvectors.
     */
    internal fun principal(rows: List<FloatArray>, count: Int): List<Pair<FloatArray, Double>> {
        val size = rows.first().size
        val block = minOf(count + 4, size, rows.size)
        val random = java.util.Random(31L)
        var q = List(block) { FloatArray(size) { random.nextFloat() - 0.5f } }
        orthonormalize(q)
        repeat(4) {
            // Q ← orth(RᵀR Q)
            val next = List(block) { FloatArray(size) }
            val y = FloatArray(block)
            for (row in rows) {
                for (j in 0 until block) y[j] = dot(row, q[j])
                for (j in 0 until block) { val v = y[j]; if (v != 0f) { val n = next[j]; for (i in 0 until size) n[i] += v * row[i] } }
            }
            orthonormalize(next)
            q = next
        }
        // Rayleigh-Ritz: G = (RQ)ᵀ(RQ), whose eigenvectors turn Q into the principal directions.
        val g = Array(block) { DoubleArray(block) }
        val y = DoubleArray(block)
        for (row in rows) {
            for (j in 0 until block) y[j] = dot(row, q[j]).toDouble()
            for (j in 0 until block) for (l in j until block) g[j][l] += y[j] * y[l]
        }
        for (j in 0 until block) for (l in 0 until j) g[j][l] = g[l][j]
        val (values, vectors) = jacobiEigen(g)
        return values.indices.sortedByDescending { values[it] }.take(count).filter { values[it] > 0.0 }.map { e ->
            val v = FloatArray(size)
            for (j in 0 until block) { val c = vectors[j][e].toFloat(); for (i in 0 until size) v[i] += c * q[j][i] }
            normalize(v)
            v to values[e]
        }
    }

    /** Modified Gram-Schmidt in place; a vector that vanishes is left at zero. */
    private fun orthonormalize(vectors: List<FloatArray>) {
        for ((j, v) in vectors.withIndex()) {
            repeat(2) { for (l in 0 until j) { val d = dot(v, vectors[l]); for (i in v.indices) v[i] -= d * vectors[l][i] } }
            normalize(v)
        }
    }

    /** Eigenvalues and eigenvectors (as columns) of the symmetric [m], by cyclic Jacobi rotations. */
    private fun jacobiEigen(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = m.size
        val a = Array(n) { m[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        repeat(60) {
            var off = 0.0
            for (p in 0 until n) for (r in p + 1 until n) off += a[p][r] * a[p][r]
            if (off < 1e-22) return@repeat
            for (p in 0 until n) for (r in p + 1 until n) {
                if (abs(a[p][r]) < 1e-300) continue
                val theta = (a[r][r] - a[p][p]) / (2 * a[p][r])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]; val akr = a[k][r]
                    a[k][p] = c * akp - s * akr; a[k][r] = s * akp + c * akr
                }
                for (k in 0 until n) {
                    val apk = a[p][k]; val ark = a[r][k]
                    a[p][k] = c * apk - s * ark; a[r][k] = s * apk + c * ark
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]; val vkr = v[k][r]
                    v[k][p] = c * vkp - s * vkr; v[k][r] = s * vkp + c * vkr
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
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
