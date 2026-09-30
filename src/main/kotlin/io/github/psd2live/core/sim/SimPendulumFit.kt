package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.RigPhysicsEdit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * Fits a Cubism pendulum to a recorded motion. The pendulum has one segment per mode, and the angle of
 * each of its vertices becomes one mode parameter; the simulated motion (as coordinates in a few principal
 * directions) is explained as a linear mix of those angles. The fit searches the segment values and input
 * weights, runs each candidate through [PhysicsEngine] - the evaluation the exported model gets - and keeps
 * the one whose angles explain the most of the motion. A multi-segment pendulum lags each vertex behind the
 * one above, so a whip-like strand is not squeezed into one in-phase swing.
 */
internal object SimPendulumFit {
    class Result(
        val setting: RigPhysicsEdit,
        /** Per output, the motion coordinates one unit of its parameter adds. */
        val readout: List<FloatArray>,
        /** Per output, its parameter value frame by frame as the pendulum plays it. */
        val played: List<FloatArray>,
        /** Share of the motion the pendulum explains, 0..1 (can be negative for a hopeless fit). */
        val r2: Float,
    )

    /** Parameter values past this percentile of a vertex's swing are clamped at ±1. */
    private const val SCALE_PERCENTILE = 0.98f
    private const val RIDGE = 1e-2

    /**
     * [inputs] name the parameters and their types; [track] holds their values per frame, one array per
     * input; [motion] is the motion per frame as coordinates in a few principal directions. [outputs] names
     * one parameter per segment. Frames are [dt] apart, the engine's own rate.
     */
    fun fit(
        id: String,
        name: String,
        outputs: List<String>,
        inputs: List<PhysicsInput>,
        ranges: Map<String, PhysicsEngine.Range>,
        track: List<FloatArray>,
        motion: List<FloatArray>,
        dt: Float,
        /** Called between candidates; throw from it to stop. */
        check: () -> Unit = {},
    ): Result {
        val frames = motion.size
        val m = motion.firstOrNull()?.size ?: 0
        val n = outputs.size
        require(n in 1..RigSimEdit.MAX_MODES && inputs.isNotEmpty() && track.size == inputs.size && track.all { it.size == frames } && m > 0)
        val allRanges = ranges + outputs.associateWith { PhysicsEngine.Range(-1f, 1f, 0f) }
        val total = motion.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)

        // The search vector: length, mobility, delay and acceleration of the first segment; each later
        // segment's length and delay as ratios to it; then one signed weight per input.
        val shared = 4 + 2 * (n - 1)
        fun setting(p: DoubleArray, scales: FloatArray): RigPhysicsEdit {
            val length = exp(p[0]).toFloat().coerceIn(0.5f, 60f)
            val mobility = (1.0 / (1.0 + exp(-p[1]))).toFloat().coerceIn(0.01f, 1f)
            val delay = exp(p[2]).toFloat().coerceIn(0.05f, 5f)
            val acceleration = exp(p[3]).toFloat().coerceIn(0.01f, 10f)
            val weights = inputs.indices.map { tanh(p[shared + it]).toFloat() }
            return RigPhysicsEdit(
                id, name,
                inputs = inputs.mapIndexed { i, input -> input.copy(weight = (abs(weights[i]) * 100f).coerceIn(0f, 100f), reflect = weights[i] < 0f) },
                outputs = outputs.mapIndexed { k, parameter -> PhysicsOutput(parameter, k + 1, scales[k].coerceAtLeast(1e-4f)) },
                segments = List(n) { k ->
                    if (k == 0) PhysicsSegment(length, mobility, delay, acceleration)
                    else PhysicsSegment((length * exp(p[4 + 2 * (k - 1)]).toFloat()).coerceIn(0.5f, 60f), mobility,
                        (delay * exp(p[5 + 2 * (k - 1)]).toFloat()).coerceIn(0.05f, 5f), acceleration)
                },
                normalization = PhysicsNormalization(angleMin = -30f, angleMax = 30f),
            )
        }

        /** Each vertex's raw angle (radians) per frame. */
        fun angles(p: DoubleArray): List<FloatArray> {
            val engine = PhysicsEngine(listOf(setting(p, FloatArray(n) { 1f })), allRanges, fps = 1f / dt)
            val strand = engine.strands.single()
            val values = HashMap<String, Float>()
            val out = List(n) { FloatArray(frames) }
            for (f in 0 until frames) {
                for ((i, input) in inputs.withIndex()) values[input.parameter] = track[i][f]
                engine.step(values, dt)
                for (k in 0 until n) out[k][f] = strand.current[k]
            }
            return out
        }

        /** The least-squares readout of [signals] onto the motion and the error it leaves. */
        fun regress(signals: List<FloatArray>): Pair<List<FloatArray>, Double> {
            // Normal equations (SᵀS + λI) B = SᵀY, n ≤ 3.
            val a = Array(n) { DoubleArray(n) }
            val b = Array(n) { DoubleArray(m) }
            for (f in 0 until frames) for (k in 0 until n) {
                val sk = signals[k][f].toDouble()
                for (j in 0 until n) a[k][j] += sk * signals[j][f]
                for (d in 0 until m) b[k][d] += sk * motion[f][d]
            }
            val scale = (0 until n).maxOf { a[it][it] }.coerceAtLeast(1e-12)
            for (k in 0 until n) a[k][k] += RIDGE * scale
            val solved = solve(a, b) ?: return List(n) { FloatArray(m) } to total
            var error = 0.0
            for (f in 0 until frames) for (d in 0 until m) {
                var predicted = 0.0
                for (k in 0 until n) predicted += signals[k][f] * solved[k][d]
                val e = motion[f][d] - predicted
                error += e * e
            }
            return solved.map { row -> FloatArray(m) { row[it].toFloat() } } to error
        }

        val cost = { p: DoubleArray -> check(); regress(angles(p)).second }
        var best: DoubleArray? = null
        var bestCost = Double.MAX_VALUE
        val starts = listOf(
            doubleArrayOf(ln(10.0), 2.2, ln(0.9), ln(1.2)),
            doubleArrayOf(ln(20.0), 1.5, ln(1.5), ln(0.6)),
            doubleArrayOf(ln(5.0), 3.0, ln(0.5), ln(2.0)),
        )
        for (start in starts) {
            // Later segments start as copies of the first; inputs start at half weight.
            val p = NelderMead.minimize(start + DoubleArray(shared - 4) + DoubleArray(inputs.size) { 0.5 }, 0.6, cost,
                iterations = 80 * (shared + inputs.size))
            val c = cost(p)
            if (c < bestCost) { bestCost = c; best = p }
        }
        val p = requireNotNull(best)
        val raw = angles(p)
        val (readout, error) = regress(raw)
        // Each parameter spans its vertex's swing: ±1 at the 98th percentile of the angle.
        val scales = FloatArray(n) { k ->
            val sorted = raw[k].map(::abs).sorted()
            val peak = sorted.getOrElse(((sorted.size - 1) * SCALE_PERCENTILE).toInt()) { 0f }
            if (peak > 1e-6f) 1f / peak else 1f
        }
        return Result(
            setting(p, scales),
            readout.mapIndexed { k, row -> FloatArray(m) { row[it] / scales[k] } },
            raw.mapIndexed { k, angle -> FloatArray(frames) { (angle[it] * scales[k]).coerceIn(-1f, 1f) } },
            (1.0 - error / total).toFloat(),
        )
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private fun solve(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = a.map { it.copyOf() }.toTypedArray()
        val r = b.map { it.copyOf() }.toTypedArray()
        for (c in 0 until n) {
            val pivot = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[pivot][c]) < 1e-18) return null
            m[c] = m[pivot].also { m[pivot] = m[c] }; r[c] = r[pivot].also { r[pivot] = r[c] }
            for (i in 0 until n) if (i != c) {
                val f = m[i][c] / m[c][c]
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
