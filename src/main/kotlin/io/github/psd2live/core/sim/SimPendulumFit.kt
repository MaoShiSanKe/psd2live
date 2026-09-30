package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a Cubism pendulum to a recorded motion. The pendulum's last [outputs] vertices each drive one mode
 * parameter; the simulated motion (as coordinates in a few principal directions) is read out as a mix of
 * their angles.
 *
 * The pendulum is judged the way the exported model is watched, not by fit alone: every candidate plays the
 * whole training motion through [PhysicsEngine] at its real amplitude, and its score adds to the motion it
 * misses how badly it misses the body settling after a move (the follow-through) and how much jerkier it
 * is than the simulation. A first guess at the input weights and types comes from small-swing
 * superposition (alternating least squares over each input's own response); from there Nelder-Mead
 * searches the segments and the weights together. A multi-segment pendulum lags each vertex behind the one
 * above, so a whip-like strand is not squeezed into one in-phase swing.
 */
internal object SimPendulumFit {
    class Result(
        val setting: RigPhysicsEdit,
        /** Per output, its parameter value frame by frame as the pendulum plays it. */
        val played: List<FloatArray>,
        /** The same over the held-out track. */
        val heldOut: List<FloatArray>,
        /** Share of the motion a straight readout of the angles explains, 0..1. */
        val r2: Float,
    )

    /** How far past the largest swing of all the motion ±1 lies. */
    private const val HEADROOM = 1.1f
    private const val RIDGE = 1e-2
    private const val ALS_ROUNDS = 6
    /**
     * The weight (0..1) each input's own response is taken at for the first guess: small enough that the
     * pendulum answers in proportion, so the responses add up to what the inputs do together.
     */
    private const val NOMINAL = 0.25f
    /** Below this weight (0..1) an input is left out of the pendulum. */
    private const val MIN_WEIGHT = 0.005f
    /** How much jerkier than the simulation the played motion may be before it costs. */
    private const val JERK_ALLOWANCE = 1.2
    private const val JERK_COST = 0.5
    /** How much the motion missed while the inputs stand still - the swing-out and settle - counts on top. */
    private const val SETTLE_COST = 0.5
    private val NORMALIZATION = PhysicsNormalization(angleMin = -30f, angleMax = 30f)
    private val TYPES = listOf(PhysicsSourceType.X, PhysicsSourceType.ANGLE)

    /**
     * [inputs] name the parameters; [track] holds their values per frame, one array per input; [motion] is
     * the motion per frame as coordinates in a few principal directions. [outputs] names one parameter per
     * driven vertex, the last of [segments]. Frames are [dt] apart; the pendulum steps at [fps] like the
     * exported file.
     */
    fun fit(
        id: String,
        name: String,
        outputs: List<String>,
        inputs: List<String>,
        ranges: Map<String, PhysicsEngine.Range>,
        track: List<FloatArray>,
        motion: List<FloatArray>,
        dt: Float,
        fps: Float,
        segments: Int = outputs.size,
        /** Runs the searches from each start on several cores. */
        parallel: Boolean = true,
        /** Frames where the track starts over from rest; the pendulum is reset there too. */
        starts: Set<Int> = emptySet(),
        /** A stretch of motion kept out of the fit, from rest: among the candidates the one that does best on it wins. */
        heldOutTrack: List<FloatArray> = emptyList(),
        heldOutMotion: List<FloatArray> = emptyList(),
        /** The pendulum of an earlier bake of this simulation: searched from, closely, instead of from scratch. */
        previous: RigPhysicsEdit? = null,
        /** Called between candidates; throw from it to stop. */
        check: () -> Unit = {},
        /** How much each frame of [motion] counts, so each kind of motion counts alike however large; null is 1. */
        weights: FloatArray? = null,
    ): Result {
        val frames = motion.size
        require(heldOutMotion.isEmpty() || heldOutTrack.size == inputs.size && heldOutTrack.all { it.size == heldOutMotion.size })
        val m = motion.firstOrNull()?.size ?: 0
        val n = outputs.size
        require(n in 1..RigSimEdit.MAX_MODES && segments >= n && inputs.isNotEmpty() && track.size == inputs.size &&
            track.all { it.size == frames } && m > 0)
        // Fitting reads raw angles: unit scale, and ranges wide enough that nothing clamps.
        val open = ranges + outputs.associateWith { PhysicsEngine.Range(-1e6f, 1e6f, 0f) }
        val first = segments - n + 1

        // The segment part of the search: length, mobility, delay and acceleration of the first segment,
        // then each later segment's length and delay as ratios to it.
        val size = 4 + 2 * (segments - 1)
        fun segmentsOf(p: DoubleArray): List<PhysicsSegment> {
            val length = exp(p[0]).toFloat().coerceIn(0.5f, 60f)
            val mobility = (1.0 / (1.0 + exp(-p[1]))).toFloat().coerceIn(0.01f, 1f)
            val delay = exp(p[2]).toFloat().coerceIn(0.05f, 5f)
            val acceleration = exp(p[3]).toFloat().coerceIn(0.01f, 10f)
            return List(segments) { k ->
                if (k == 0) PhysicsSegment(length, mobility, delay, acceleration)
                else PhysicsSegment((length * exp(p[4 + 2 * (k - 1)]).toFloat()).coerceIn(0.5f, 60f), mobility,
                    (delay * exp(p[5 + 2 * (k - 1)]).toFloat()).coerceIn(0.05f, 5f), acceleration)
            }
        }
        fun segmentVector(chain: List<PhysicsSegment>): DoubleArray {
            val p = DoubleArray(size)
            val s0 = chain.first()
            val mobility = s0.mobility.coerceIn(0.02f, 0.98f).toDouble()
            p[0] = ln(s0.length.toDouble()); p[1] = ln(mobility / (1 - mobility))
            p[2] = ln(s0.delay.toDouble()); p[3] = ln(s0.acceleration.toDouble())
            for (k in 1 until segments) {
                p[4 + 2 * (k - 1)] = ln(chain[k].length.toDouble() / s0.length)
                p[5 + 2 * (k - 1)] = ln(chain[k].delay.toDouble() / s0.delay)
            }
            return p
        }
        fun setting(chain: List<PhysicsSegment>, links: List<PhysicsInput>, scales: FloatArray) = RigPhysicsEdit(
            id, name, inputs = links,
            outputs = outputs.mapIndexed { k, parameter -> PhysicsOutput(parameter, first + k, scales[k].coerceAtLeast(1e-4f)) },
            segments = chain, normalization = NORMALIZATION,
        )

        /** The driven vertices' angles (radians) per frame as [links] play [values] through [chain]. */
        fun run(chain: List<PhysicsSegment>, links: List<PhysicsInput>, values: List<FloatArray> = track, restarts: Set<Int> = starts): Array<FloatArray> {
            val engine = PhysicsEngine(listOf(setting(chain, links, FloatArray(n) { 1f })), open, fps)
            val pose = HashMap<String, Float>()
            val length = values.first().size
            val out = Array(n) { FloatArray(length) }
            val used = links.map { link -> inputs.indexOf(link.parameter) }
            for (f in 0 until length) {
                if (f in restarts) engine.reset()
                for ((i, link) in links.withIndex()) pose[link.parameter] = values[used[i]][f]
                val driven = engine.step(pose, dt)
                for (k in 0 until n) out[k][f] = driven[outputs[k]] ?: 0f
            }
            return out
        }

        val training = Judge(track, motion, starts, n, m, weights)
        val judged = if (heldOutMotion.isEmpty()) null else Judge(heldOutTrack, heldOutMotion, setOf(0), n, m, null)

        /** [coefficients] on the nominal responses as pendulum inputs of [types]. */
        fun links(coefficients: FloatArray, types: List<PhysicsSourceType>) = inputs.indices.mapNotNull { i ->
            val w = coefficients[i] * NOMINAL
            if (abs(w) < MIN_WEIGHT) null else PhysicsInput(inputs[i], (abs(w) * 100f).coerceIn(0f, 100f), types[i], reflect = w < 0f)
        }.ifEmpty { listOf(PhysicsInput(inputs.first(), 100f * NOMINAL, types.first())) }

        /** A first guess at the inputs for [chain]: each input's small-swing response, both types, mixed by least squares. */
        fun guess(chain: List<PhysicsSegment>): List<PhysicsInput> {
            val jobs = inputs.flatMap { parameter -> TYPES.map { type -> PhysicsInput(parameter, 100f * NOMINAL, type) } }
            val channels = jobs.map { run(chain, listOf(it)) }
            val both = Gram(channels, motion)
            val weights = FloatArray(inputs.size * TYPES.size) { if (it % 2 == 0) 0.5f else 0.2f }
            both.als(weights)
            // One type per input, the one that does more.
            val types = inputs.indices.map { i ->
                if (abs(weights[i * 2]) * sqrt(both.energy(i * 2)) >= abs(weights[i * 2 + 1]) * sqrt(both.energy(i * 2 + 1))) PhysicsSourceType.X
                else PhysicsSourceType.ANGLE
            }
            val picked = inputs.indices.map { i -> i * 2 + TYPES.indexOf(types[i]) }
            val single = FloatArray(inputs.size) { weights[picked[it]] }
            Gram(picked.map { channels[it] }, motion).als(single)
            return links(single, types)
        }

        class Candidate(val chain: List<PhysicsSegment>, val links: List<PhysicsInput>, val score: Double)

        /**
         * Nelder-Mead over [chain]'s segments and [links]' weights together (their types and directions
         * stay), each point played for real and scored; returns the best point it saw.
         */
        fun search(chain: List<PhysicsSegment>, links: List<PhysicsInput>, step: Double, iterations: Int): Candidate {
            fun linksOf(p: DoubleArray) = links.mapIndexed { i, link ->
                link.copy(weight = (100.0 / (1.0 + exp(-p[size + i]))).toFloat().coerceIn(0.1f, 100f))
            }
            val start = segmentVector(chain) + DoubleArray(links.size) { i ->
                val w = (links[i].weight / 100.0).coerceIn(0.01, 0.99); ln(w / (1 - w))
            }
            var best: Candidate? = null
            val cost = { p: DoubleArray ->
                check()
                val c = segmentsOf(p); val l = linksOf(p)
                val score = training.score(run(c, l), null).first
                if (best == null || score < best!!.score) best = Candidate(c, l, score)
                score
            }
            NelderMead.minimize(start, step, cost, iterations = iterations, tolerance = 1e-4)
            return requireNotNull(best)
        }

        // Where to search from: the previous bake's pendulum, closely, when it fits this one; otherwise a few
        // spread-out pendulums, each later segment a copy of the first, with the first guess at the inputs.
        val reuse = previous?.takeIf { p -> p.segments.size == segments && p.inputs.isNotEmpty() && p.inputs.all { it.parameter in inputs } }
        val jobs: List<() -> Candidate> = if (reuse != null) listOf({ search(reuse.segments, reuse.inputs, 0.25, 12 * (size + reuse.inputs.size)) })
        else listOf(
            doubleArrayOf(ln(10.0), 2.2, ln(0.9), ln(1.2)),
            doubleArrayOf(ln(20.0), 1.5, ln(1.5), ln(0.6)),
            doubleArrayOf(ln(5.0), 3.0, ln(0.5), ln(2.0)),
        ).map { start -> {
            val chain = segmentsOf(start + DoubleArray(size - 4))
            val links = guess(chain)
            search(chain, links, 0.5, 25 * (size + links.size))
        } }
        val found = if (parallel) jobs.parallelStream().map { it() }.toList() else jobs.map { it() }

        // The candidate that does best on the held-out motion, read out as on the training.
        fun heldOutScore(candidate: Candidate): Double {
            judged ?: return candidate.score
            val readout = training.score(run(candidate.chain, candidate.links), null).second
            return judged.score(run(candidate.chain, candidate.links, heldOutTrack, setOf(0)), readout).first
        }
        val chosen = found.minBy(::heldOutScore)
        val raw = run(chosen.chain, chosen.links)
        val rawHeldOut = if (judged == null) null else run(chosen.chain, chosen.links, heldOutTrack, setOf(0))
        // Each parameter spans its vertex's whole swing over all the motion, the hardest shaking included, with
        // room to spare: a parameter held at ±1 holds the body still while it should swing.
        val scales = FloatArray(n) { k ->
            var peak = raw[k].maxOf(::abs)
            rawHeldOut?.let { peak = maxOf(peak, it[k].maxOf(::abs)) }
            if (peak > 1e-6f) 1f / (peak * HEADROOM) else 1f
        }
        fun clamp(angles: Array<FloatArray>) = angles.mapIndexed { k, angle -> FloatArray(angle.size) { (angle[it] * scales[k]).coerceIn(-1f, 1f) } }
        val played = clamp(raw)
        val total = motion.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        val error = regress(played, motion, n, m).second
        return Result(setting(chosen.chain, chosen.links, scales), played, rawHeldOut?.let(::clamp) ?: emptyList(), (1.0 - error / total).toFloat())
    }

    /**
     * Scores played angles against [motion] over [track]: the share of the motion missed, the share missed
     * while every input stands still counted again by [SETTLE_COST], and each piece's jerk past
     * [JERK_ALLOWANCE] times the simulation's.
     */
    private class Judge(track: List<FloatArray>, val motion: List<FloatArray>, val starts: Set<Int>, val n: Int, val m: Int, val weights: FloatArray?) {
        private val frames = motion.size
        private fun w(f: Int) = weights?.get(f)?.toDouble() ?: 1.0
        private val total = (0 until frames).sumOf { f -> w(f) * motion[f].sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        /** Frames where no input has moved for a tenth of a second or more. */
        private val still = BooleanArray(frames).also { still ->
            var run = 0
            for (f in 0 until frames) {
                val moving = f > 0 && f !in starts && track.any { abs(it[f] - it[f - 1]) > 1e-5f }
                run = if (moving || f in starts) 0 else run + 1
                still[f] = run >= 6
            }
        }
        private val stillTotal = (0 until frames).sumOf { f -> if (still[f]) w(f) * motion[f].sumOf { (it * it).toDouble() } else 0.0 }
        /** Each piece's first frame and the frame past its last. */
        private val pieces = (starts.filter { it in 0 until frames } + 0).distinct().sorted().let { at -> at.zip(at.drop(1) + frames) }
        private val targetJerk = pieces.map { (from, to) -> jerk(from, to) { f, d -> motion[f][d].toDouble() } }

        /** The energy of the third differences within [from] until [to]. */
        private fun jerk(from: Int, to: Int, value: (Int, Int) -> Double): Double {
            var sum = 0.0
            for (f in from + 3 until to) for (d in 0 until m) {
                val j = value(f, d) - 3 * value(f - 1, d) + 3 * value(f - 2, d) - value(f - 3, d)
                sum += j * j
            }
            return sum
        }

        /** The score of [angles] with [readout] (fitted by least squares when null), and the readout. */
        fun score(angles: Array<FloatArray>, readout: List<FloatArray>?): Pair<Double, List<FloatArray>> {
            val b = readout ?: regress(angles.toList(), motion, n, m, weights).first
            val predicted = Array(frames) { f -> DoubleArray(m) { d -> var s = 0.0; for (k in 0 until n) s += angles[k][f] * b[k][d]; s } }
            var missed = 0.0; var stillMissed = 0.0
            for (f in 0 until frames) {
                val wf = w(f)
                for (d in 0 until m) {
                    val e = motion[f][d] - predicted[f][d]
                    missed += wf * e * e
                    if (still[f]) stillMissed += wf * e * e
                }
            }
            // Jerk is judged piece by piece: a pendulum that trembles through small swaying must not hide
            // behind the large swings of another piece.
            var jerky = 0.0
            for ((p, piece) in pieces.withIndex()) {
                if (targetJerk[p] <= 1e-12) continue
                val ratio = sqrt(jerk(piece.first, piece.second) { f, d -> predicted[f][d] } / targetJerk[p])
                val excess = maxOf(0.0, ratio - JERK_ALLOWANCE)
                jerky += excess * excess / pieces.size
            }
            val settle = if (stillTotal > total * 1e-3) SETTLE_COST * stillMissed / stillTotal else 0.0
            return missed / total + settle + JERK_COST * jerky to b
        }
    }

    /**
     * The sums alternating least squares needs for motion ≈ Σ_k B_k Σ_c w_c a_ck, taken once over the
     * frames: channel against channel (P) and channel against motion (Q). Each round is then independent
     * of the number of frames.
     */
    private class Gram(channels: List<Array<FloatArray>>, motion: List<FloatArray>) {
        val c = channels.size
        val n = channels.first().size
        val m = motion.first().size
        private val size = c * n
        /** P[x·size + y] = Σ_f a_x(f) a_y(f), x = j·n + k indexing channel j's vertex k. */
        private val p = DoubleArray(size * size)
        /** Q[x·m + d] = Σ_f a_x(f) y_d(f). */
        private val q = DoubleArray(size * m)
        private val total = motion.sumOf { row -> row.sumOf { (it * it).toDouble() } }

        init {
            val frames = motion.size
            val flat = Array(size) { channels[it / n][it % n] }
            for (x in 0 until size) {
                val ax = flat[x]
                for (y in x until size) {
                    val ay = flat[y]
                    var s = 0.0
                    for (f in 0 until frames) s += ax[f] * ay[f]
                    p[x * size + y] = s; p[y * size + x] = s
                }
                for (f in 0 until frames) {
                    val v = ax[f]
                    if (v == 0f) continue
                    val row = motion[f]
                    for (d in 0 until m) q[x * m + d] += v * row[d]
                }
            }
        }

        /** How much channel [j] moves on its own, summed over its vertices. */
        fun energy(j: Int): Double {
            var s = 0.0
            for (k in 0 until n) { val x = j * n + k; s += p[x * size + x] }
            return s
        }

        /** Rounds of readout-then-weights from [weights], updated in place and kept within ±1 / [NOMINAL]. Returns the error left. */
        fun als(weights: FloatArray): Double {
            var error = total
            for (round in 0..ALS_ROUNDS) {
                // The readout for the weights: G B = H.
                val g = Array(n) { DoubleArray(n) }
                val h = Array(n) { DoubleArray(m) }
                for (j in 0 until c) {
                    val wj = weights[j].toDouble()
                    if (wj == 0.0) continue
                    for (k in 0 until n) {
                        val x = j * n + k
                        for (d in 0 until m) h[k][d] += wj * q[x * m + d]
                        for (l in 0 until c) {
                            val wl = weights[l].toDouble()
                            if (wl == 0.0) continue
                            for (k2 in 0 until n) g[k][k2] += wj * wl * p[x * size + l * n + k2]
                        }
                    }
                }
                val scale = (0 until n).maxOf { g[it][it] }.coerceAtLeast(1e-12)
                val ridged = Array(n) { k -> DoubleArray(n) { g[k][it] + if (it == k) RIDGE * scale else 0.0 } }
                val b = solve(ridged, h) ?: return error
                error = total
                for (k in 0 until n) for (d in 0 until m) {
                    error -= 2 * b[k][d] * h[k][d]
                    for (k2 in 0 until n) error += b[k][d] * g[k][k2] * b[k2][d]
                }
                if (round == ALS_ROUNDS) break
                // The weights for the readout: M w = v, M_jl = Σ_kk' P(jk, lk') B_k·B_k', v_j = Σ_k Q(jk)·B_k.
                val bb = Array(n) { k -> DoubleArray(n) { k2 -> var s = 0.0; for (d in 0 until m) s += b[k][d] * b[k2][d]; s } }
                val mm = Array(c) { DoubleArray(c) }
                val v = Array(c) { DoubleArray(1) }
                for (j in 0 until c) for (k in 0 until n) {
                    val x = j * n + k
                    var s = 0.0
                    for (d in 0 until m) s += q[x * m + d] * b[k][d]
                    v[j][0] += s
                    for (l in 0 until c) for (k2 in 0 until n) mm[j][l] += p[x * size + l * n + k2] * bb[k][k2]
                }
                val diagonal = (0 until c).maxOf { mm[it][it] }.coerceAtLeast(1e-12)
                for (j in 0 until c) mm[j][j] += 1e-6 * diagonal
                val w = solve(mm, v) ?: break
                val most = (0 until c).maxOf { abs(w[it][0]) }
                if (most < 1e-9) break
                val shrink = if (most > 1.0 / NOMINAL) 1.0 / NOMINAL / most else 1.0
                for (j in 0 until c) weights[j] = (w[j][0] * shrink).toFloat()
            }
            return error.coerceAtLeast(0.0)
        }
    }

    /** The ridge least-squares readout of [signals] onto the motion and the error it leaves. */
    internal fun regress(signals: List<FloatArray>, motion: List<FloatArray>, n: Int, m: Int, weights: FloatArray? = null): Pair<List<FloatArray>, Double> {
        val frames = motion.size
        val total = (0 until frames).sumOf { f -> (weights?.get(f)?.toDouble() ?: 1.0) * motion[f].sumOf { (it * it).toDouble() } }
        // Normal equations (SᵀWS + λI) B = SᵀWY, n ≤ 3.
        val a = Array(n) { DoubleArray(n) }
        val b = Array(n) { DoubleArray(m) }
        for (f in 0 until frames) for (k in 0 until n) {
            val sk = signals[k][f].toDouble() * (weights?.get(f)?.toDouble() ?: 1.0)
            if (sk == 0.0) continue
            for (j in 0 until n) a[k][j] += sk * signals[j][f]
            val row = motion[f]
            for (d in 0 until m) b[k][d] += sk * row[d]
        }
        val scale = (0 until n).maxOf { a[it][it] }.coerceAtLeast(1e-12)
        for (k in 0 until n) a[k][k] += RIDGE * scale
        val solved = solve(a, b) ?: return List(n) { FloatArray(m) } to total
        // ‖Y - SB‖² = ‖Y‖² - 2 tr(BᵀSᵀY) + tr(BᵀSᵀSB), without another pass over the frames.
        var error = total
        for (k in 0 until n) for (d in 0 until m) {
            error -= 2 * solved[k][d] * b[k][d]
            for (j in 0 until n) error += solved[k][d] * (a[k][j] - if (j == k) RIDGE * scale else 0.0) * solved[j][d]
        }
        return solved.map { row -> FloatArray(m) { row[it].toFloat() } } to error.coerceAtLeast(0.0)
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    internal fun solve(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = a.map { it.copyOf() }.toTypedArray()
        val r = b.map { it.copyOf() }.toTypedArray()
        for (c in 0 until n) {
            val pivot = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[pivot][c]) < 1e-18) return null
            m[c] = m[pivot].also { m[pivot] = m[c] }; r[c] = r[pivot].also { r[pivot] = r[c] }
            for (i in 0 until n) if (i != c) {
                val f = m[i][c] / m[c][c]
                if (f == 0.0) continue
                for (j in c until n) m[i][j] -= f * m[c][j]
                for (j in r[i].indices) r[i][j] -= f * r[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(r[i].size) { r[i][it] / m[i][i] } }
    }
}

/** Downhill simplex minimization; enough for a handful of smooth parameters. */
internal object NelderMead {
    fun minimize(start: DoubleArray, step: Double, f: (DoubleArray) -> Double, iterations: Int = 400, tolerance: Double = 1e-7): DoubleArray {
        val n = start.size
        val points = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val values = DoubleArray(n + 1) { f(points[it]) }
        repeat(iterations) {
            val order = values.indices.sortedBy { values[it] }
            val sortedPoints = order.map { points[it] }; val sortedValues = order.map { values[it] }
            for (i in 0..n) { points[i] = sortedPoints[i]; values[i] = sortedValues[i] }
            if (abs(values[n] - values[0]) <= tolerance * (abs(values[0]) + 1e-12)) return points[0]
            val centroid = DoubleArray(n) { d -> (0 until n).sumOf { points[it][d] } / n }
            fun toward(t: Double) = DoubleArray(n) { d -> centroid[d] + t * (points[n][d] - centroid[d]) }
            val reflected = toward(-1.0); val fr = f(reflected)
            when {
                fr < values[0] -> {
                    val expanded = toward(-2.0); val fe = f(expanded)
                    if (fe < fr) { points[n] = expanded; values[n] = fe } else { points[n] = reflected; values[n] = fr }
                }
                fr < values[n - 1] -> { points[n] = reflected; values[n] = fr }
                else -> {
                    val contracted = if (fr < values[n]) toward(-0.5) else toward(0.5)
                    val fc = f(contracted)
                    if (fc < minOf(fr, values[n])) { points[n] = contracted; values[n] = fc } else {
                        for (i in 1..n) {
                            points[i] = DoubleArray(n) { d -> points[0][d] + 0.5 * (points[i][d] - points[0][d]) }
                            values[i] = f(points[i])
                        }
                    }
                }
            }
        }
        return points[values.indices.minBy { values[it] }]
    }
}
