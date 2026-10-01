package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.RigEditOverlay
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Reduces a simulation to parameters, keyforms and pendulums (see [SimBakeResult]).
 *
 * 1. Static response: for each static input's keys the rig is posed, the body settles under
 *    gravity, and what is left against the rig becomes corrections on that parameter's own axis, so the
 *    exported model hangs exactly as the pose asks.
 * 2. Training: the dynamic inputs play [SimMotionLibrary]'s motion - the model dragged, shaken, nodded
 *    and swaying over the inputs' full ranges - each piece from rest and all of them at once on separate
 *    copies of the body; each frame records how far the body is from the rig (minus the static
 *    correction) in each mesh's local keyform space.
 * 3. Subspace: the few principal directions of that residual, measured in px at the default pose.
 * 4. Pendulum: [SimPendulumFit] finds the Cubism pendulum whose driven vertices' angles play the motion
 *    best - following it, settling like it and no jerkier - each vertex driving one -1..1 parameter that
 *    spans the vertex's whole swing over that motion, so it does not stall at ±1. The first mode carries
 *    the swing; each later one is one more segment fitted over what the modes above leave - the body
 *    bending and bunching up as it lags.
 * 5. Keys: evenly spread over -1..1, their shapes solved mode by mode, each by least squares over every
 *    frame as the pendulum plays it - arcs and one-sided pushes included - over what the modes before it
 *    leave, and kept on a smooth curve so the body's speed does not jump at a key.
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
    /** How strongly the key shapes of a mode are kept on a smooth curve rather than turning corners at keys. */
    private const val KEY_BEND = 0.3
    /** How much more a corner at the rest key costs than one at another key. */
    private const val CENTER_BEND = 10.0
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
        val staticAxes = edit.staticInputs.orEmpty().mapNotNull { raw ->
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
        val ranged = inputs.map { parameters.getValue(it) }
        /** Library values (-1..1 about the default) as parameter values. */
        fun values(piece: List<FloatArray>) = ranged.indices.map { i ->
            val p = ranged[i]
            FloatArray(piece[i].size) { f -> val u = piece[i][f]; if (u >= 0f) p.default + u * (p.max - p.default) else p.default + u * (p.default - p.min) }
        }
        val pieces = SimMotionLibrary.training(inputs.size, options.fps, options.duration).map(::values)
        val heldOutPiece = values(SimMotionLibrary.heldOut(inputs.size, options.fps, options.duration))
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
        // Each piece counts alike: shaking the body through its resonance moves it far more than a drag, and
        // would otherwise decide the pendulum and the keys on its own.
        val weights = FloatArray(residuals.size)
        run {
            var at = 0
            val means = recorded.map { piece ->
                val energy = (at until at + piece.size).sumOf { f -> metric[f].sumOf { (it * it).toDouble() } } / piece.size.coerceAtLeast(1)
                at += piece.size
                energy
            }
            val overall = total / residuals.size
            at = 0
            for ((p, piece) in recorded.withIndex()) {
                val w = if (means[p] > 1e-12) (overall / means[p]).coerceIn(0.1, 10.0).toFloat() else 1f
                for (f in at until at + piece.size) weights[f] = w
                at += piece.size
            }
        }
        val heldOutMetric = heldOutResiduals.map(space::toPx)
        val heldOutMotion = heldOutMetric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }

        // 4. The pendulum.
        options.progress(0.65f)
        val outputs = (1..edit.modes).map { SimGenerator.parameterId(edit, it) }
        val fit = SimPendulumFit.fit(SimGenerator.physicsId(edit), edit.name, outputs, inputs, PhysicsEngine.ranges(model.parameters),
            track, motion, dt, options.physicsFps.toFloat(), segments = edit.modes + options.extraSegments, starts = starts,
            heldOutTrack = heldOutPiece, heldOutMotion = heldOutMotion, previous = options.previous, check = ::check, weights = weights)

        // 5. Key shapes for every mode at once; a mode that ends up moving nothing is dropped and the rest solved again.
        options.progress(0.95f)
        val keys = edit.modeKeys
        var kept = outputs.indices.toList()
        var shapes = solveModes(residuals, kept.map { fit.played[it] }, keys, space.size, weights)
        while (kept.isNotEmpty()) {
            val moving = kept.filterIndexed { place, _ -> shapes[place].maxOf { space.motionPx(it) } >= MIN_MOTION_PX }
            if (moving.size == kept.size) break
            kept = moving
            shapes = if (kept.isEmpty()) emptyList() else solveModes(residuals, kept.map { fit.played[it] }, keys, space.size, weights)
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
        val simulated = ArrayList<FloatArray>(checkResiduals.size); val baked = ArrayList<FloatArray>(checkResiduals.size)
        val errors = FloatArray(checkResiduals.size) { f ->
            val played = FloatArray(space.size)
            for ((place, k) in kept.withIndex()) {
                val offsets = space.join(axes[place], checkPlayed[k][f])
                for (i in played.indices) played[i] += offsets[i]
            }
            simulated += space.toPx(checkResiduals[f]); baked += space.toPx(played)
            val px = FloatArray(space.size) { simulated[f][it] - baked[f][it] }
            missed += px.sumOf { (it * it).toDouble() }
            maxDistance(px)
        }
        // How far the parameters reach, how often they sit at ±1, and how smooth the baked motion is.
        val peak = kept.maxOf { k -> checkPlayed[k].maxOf(::abs) }
        val clipped = checkPlayed.first().indices.count { f -> kept.any { k -> abs(checkPlayed[k][f]) >= 0.999f } }.toFloat() / checkPlayed.first().size
        val jerk = sqrt(jerkEnergy(baked) / jerkEnergy(simulated).coerceAtLeast(1e-12)).toFloat()
        // Out to the parameters' own span: a keyform axis snaps a value within 0.001 of a key onto it, which
        // over -1..1 would jolt a large body every time a mode swings through rest.
        val range = SimGenerator.MODE_RANGE
        val physics = fit.setting.copy(outputs = fit.setting.outputs.filterIndexed { k, _ -> k in kept }.map { it.copy(scale = it.scale * range) })
        val spanned = modes.map { mode ->
            SimBakedMode(SimBakedAxis(mode.axis.parameter, FloatArray(mode.axis.keys.size) { mode.axis.keys[it] * range }, mode.axis.offsets), mode.amplitude, mode.energy)
        }
        options.progress(1f)
        return SimBakeResult(fingerprint, space.counts, statics, spanned, physics,
            (1.0 - missed / checkTotal).toFloat(), percentile(errors.toList(), 0.95f), peak, clipped, jerk)
    }

    /** [count] keys over [parameter]'s range, half on each side of its default (the default itself is one). */
    internal fun staticKeys(parameter: Parameter, count: Int): FloatArray {
        val side = (count - 1) / 2
        val below = if (parameter.default > parameter.min) List(side) { parameter.min + (parameter.default - parameter.min) * it / side } else emptyList()
        val above = if (parameter.max > parameter.default) List(side) { parameter.max - (parameter.max - parameter.default) * it / side }.reversed() else emptyList()
        return (below + parameter.default + above).distinct().toFloatArray()
    }

    /**
     * Key shapes for each mode in turn over what the modes before it leave, like the pendulum is fitted:
     * solved together, the shapes of two modes that swing nearly alike grow large and cancel. Returns per
     * mode, per key, the offsets.
     */
    internal fun solveModes(residuals: List<FloatArray>, played: List<FloatArray>, keys: FloatArray, size: Int,
                            weights: FloatArray? = null): List<List<FloatArray>> {
        var left = residuals
        return played.mapIndexed { k, values ->
            val shapes = solveKeys(left, listOf(values), listOf(keys), size, weights).single()
            if (k < played.lastIndex) left = left.mapIndexed { f, r ->
                val u = values[f].coerceIn(keys.first(), keys.last())
                var j = 0
                while (j < keys.size - 2 && u > keys[j + 1]) j++
                val t = (u - keys[j]) / (keys[j + 1] - keys[j])
                val a = shapes[j]; val b = shapes[j + 1]
                FloatArray(size) { r[it] - (a[it] * (1f - t) + b[it] * t) }
            }
            shapes
        }
    }

    /**
     * Key shapes for modes played as [played] (per mode, -1..1 per frame) at [keys] (per mode), all modes
     * solved together by least squares over [residuals]: each frame is the sum of every mode's keys
     * interpolated at its value. A key few frames reach leans toward the mode's straight-line shape. Returns
     * per mode, per key, the offsets.
     */
    internal fun solveKeys(residuals: List<FloatArray>, played: List<FloatArray>, keys: List<FloatArray>, size: Int,
                           weights: FloatArray? = null): List<List<FloatArray>> {
        val n = played.size
        val zeros = keys.map { k -> k.indexOfFirst { it == 0f } }
        val frees = keys.indices.map { m -> keys[m].indices.filter { it != zeros[m] } }
        val firsts = IntArray(n).also { for (m in 1 until n) it[m] = it[m - 1] + frees[m - 1].size }
        val unknowns = frees.sumOf { it.size }
        fun column(k: Int, key: Int) = firsts[k] + frees[k].indexOf(key)
        // The straight-line shapes: one shape per mode scaled by its value.
        val lineA = Array(n) { DoubleArray(n) }
        val lineB = Array(n) { DoubleArray(size) }
        val a = Array(unknowns) { DoubleArray(unknowns) }
        val b = Array(unknowns) { DoubleArray(size) }
        val columns = IntArray(2 * n); val values = DoubleArray(2 * n)
        for ((f, r) in residuals.withIndex()) {
            val w = weights?.get(f)?.toDouble() ?: 1.0
            var active = 0
            for (k in 0 until n) {
                val keys = keys[k]; val zero = zeros[k]
                val u = played[k][f].coerceIn(keys.first(), keys.last())
                var j = 0
                while (j < keys.size - 2 && u > keys[j + 1]) j++
                val t = ((u - keys[j]) / (keys[j + 1] - keys[j])).toDouble()
                if (j != zero && t < 1.0) { columns[active] = column(k, j); values[active++] = 1.0 - t }
                if (j + 1 != zero && t > 0.0) { columns[active] = column(k, j + 1); values[active++] = t }
                for (l in 0 until n) lineA[k][l] += w * u * played[l][f].coerceIn(-1f, 1f)
                val bk = lineB[k]
                for (i in 0 until size) bk[i] += w * u * r[i]
            }
            for (p in 0 until active) {
                for (q in 0 until active) a[columns[p]][columns[q]] += w * values[p] * values[q]
                val bp = b[columns[p]]; val v = w * values[p]
                for (i in 0 until size) bp[i] += v * r[i]
            }
        }
        val lineScale = (0 until n).maxOf { lineA[it][it] }.coerceAtLeast(1e-12)
        for (k in 0 until n) lineA[k][k] += 1e-2 * lineScale
        val line = SimPendulumFit.solve(lineA, lineB) ?: Array(n) { DoubleArray(size) }
        val scale = (0 until unknowns).maxOf { a[it][it] }.coerceAtLeast(1e-12)
        val ridge = KEY_RIDGE * scale
        for (k in 0 until n) for (key in frees[k]) {
            val c = column(k, key)
            a[c][c] += ridge
            for (i in 0 until size) b[c][i] += ridge * keys[k][key] * line[k][i]
        }
        // Bends between neighbouring keys cost: the body's speed jumps at a key where the shapes turn a
        // corner, which shows as a hitch when the parameter sweeps through it.
        val bend = KEY_BEND * scale
        for (k in 0 until n) {
            val ks = keys[k]
            for (j in 1 until ks.size - 1) {
                val h0 = (ks[j] - ks[j - 1]).toDouble(); val h1 = (ks[j + 1] - ks[j]).toDouble()
                val span = (h0 + h1) / 2
                val terms = listOf(j - 1 to span / h0, j to -span / h0 - span / h1, j + 1 to span / h1).filter { it.first != zeros[k] }
                // The rest key is crossed at every swing, small ones most of all: a corner there is a hitch
                // each time, so the shapes go straight through it.
                val weight = if (j == zeros[k]) bend * CENTER_BEND else bend
                for ((p, cp) in terms) for ((q, cq) in terms) a[column(k, p)][column(k, q)] += weight * cp * cq
            }
        }
        val solved = SimPendulumFit.solve(a, b) ?: Array(unknowns) { c ->
            val k = firsts.indexOfLast { it <= c }
            DoubleArray(size) { i -> keys[k][frees[k][c - firsts[k]]] * line[k][i] }
        }
        return List(n) { k -> keys[k].indices.map { key -> if (key == zeros[k]) FloatArray(size) else FloatArray(size) { solved[column(k, key)][it].toFloat() } } }
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

    /** The energy of the third differences of [frames]: how much the motion's acceleration jumps. */
    private fun jerkEnergy(frames: List<FloatArray>): Double {
        var sum = 0.0
        for (f in 3 until frames.size) {
            val a = frames[f]; val b = frames[f - 1]; val c = frames[f - 2]; val d = frames[f - 3]
            for (i in a.indices) { val j = (a[i] - 3 * b[i] + 3 * c[i] - d[i]).toDouble(); sum += j * j }
        }
        return sum
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
