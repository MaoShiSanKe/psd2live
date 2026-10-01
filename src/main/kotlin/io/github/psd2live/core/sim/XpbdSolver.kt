package io.github.psd2live.core.sim

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/*
 * A 2D XPBD solver ("small steps": many substeps, one constraint pass each, Macklin et al. 2019).
 *
 * Space is the evaluator's world space: canvas px with y negated, so y points up and gravity is -y. Everything is a flat
 * FloatArray / IntArray and every loop runs in index order: two runs from the same state are bit-identical,
 * which the bake relies on to replay.
 *
 * The rig moves the scene through two per-frame targets the caller writes before [XpbdSolver.step]:
 * - [SimState.anchorX]/[SimState.anchorY]: where each pinned particle's anchor is now (the pin follows it);
 * - [SimState.goalX]/[SimState.goalY]: the rest shape carried by the rig to this pose (the goal spring pulls
 *   toward it).
 * Both are interpolated across the substeps from the previous frame's values, so a fast rig motion does not
 * arrive as one jump.
 */

/** Particle state. Positions are world px (y up); [invMass] 0 is kinematic. */
class SimState(val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val invMass = FloatArray(count) { 1f }
    /** Per-particle damping rate (1/s) and force-field factor. */
    val damping = FloatArray(count)
    val windFactor = FloatArray(count) { 1f }
    val anchorX = FloatArray(count)
    val anchorY = FloatArray(count)
    val goalX = FloatArray(count)
    val goalY = FloatArray(count)
    /**
     * Rest-frame offset added to each goal, turned with the body: the pre-stress that makes the drawn shape
     * the equilibrium under gravity (see SimScene.calibrate). Zero for a shape gravity already holds.
     */
    val goalOffsetX = FloatArray(count)
    val goalOffsetY = FloatArray(count)
    /** How far the body's frame has turned from rest this frame, radians; goal offsets turn with it. */
    var frameAngle = 0f
    internal var lastFrameAngle = 0f
    internal val px = FloatArray(count)
    internal val py = FloatArray(count)
    internal val lastAnchorX = FloatArray(count)
    internal val lastAnchorY = FloatArray(count)
    internal val lastGoalX = FloatArray(count)
    internal val lastGoalY = FloatArray(count)
    internal val stepAnchorX = FloatArray(count)
    internal val stepAnchorY = FloatArray(count)
    internal val stepGoalX = FloatArray(count)
    internal val stepGoalY = FloatArray(count)

    /** Places every particle at [positions] at rest, with anchors and goals there too. */
    fun reset(positions: FloatArray) {
        require(positions.size == count * 2) { "Expected ${count * 2} coordinates" }
        for (i in 0 until count) {
            x[i] = positions[i * 2]; y[i] = positions[i * 2 + 1]
            vx[i] = 0f; vy[i] = 0f
            anchorX[i] = x[i]; anchorY[i] = y[i]; goalX[i] = x[i]; goalY[i] = y[i]
            lastAnchorX[i] = x[i]; lastAnchorY[i] = y[i]; lastGoalX[i] = x[i]; lastGoalY[i] = y[i]
        }
        frameAngle = 0f; lastFrameAngle = 0f
    }

    /**
     * Takes the current anchors and goals as the last frame's, and puts fully pinned particles on their
     * anchors, so the next step starts from rest instead of sweeping in from where [reset] left them.
     */
    fun settle() {
        for (i in 0 until count) {
            lastAnchorX[i] = anchorX[i]; lastAnchorY[i] = anchorY[i]
            lastGoalX[i] = goalX[i]; lastGoalY[i] = goalY[i]
        }
        lastFrameAngle = frameAngle
    }

    fun positions(): FloatArray = FloatArray(count * 2) { if (it % 2 == 0) x[it / 2] else y[it / 2] }
}

/**
 * Distance constraints `|a - b| = rest`, with one compliance when stretched and another when compressed
 * (XPBD compliance; 0 is rigid).
 *
 * The split is what lets a 2D mesh bend at all: a triangulated sheet whose edges all keep their length is a
 * rigid truss. Seen from the front, cloth and hair fold and foreshorten in depth but never grow longer, so
 * edges resist stretching hard and compression only as much as the material's bend stiffness.
 */
class DistanceConstraints(
    val a: IntArray,
    val b: IntArray,
    val rest: FloatArray,
    val compliance: FloatArray,
    val compressionCompliance: FloatArray = compliance,
) {
    init { require(a.size == b.size && b.size == rest.size && rest.size == compliance.size && compliance.size == compressionCompliance.size) }
    val size: Int get() = a.size

    companion object {
        val Empty = DistanceConstraints(IntArray(0), IntArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Two particles held on one point (a simulated glue pair, or two welded strips). [weightA] / [weightB]
 * are the share of the correction each side takes, as Cubism's glue pulls each side by its own weight.
 */
class WeldConstraints(val a: IntArray, val b: IntArray, val weightA: FloatArray, val weightB: FloatArray, val compliance: FloatArray) {
    init { require(a.size == b.size && b.size == weightA.size && weightA.size == weightB.size && weightB.size == compliance.size) }
    val size: Int get() = a.size

    companion object {
        val Empty = WeldConstraints(IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Long-range attachments (Kim et al. 2012): particle [particle] stays within [maxDistance] of the anchor
 * of pinned particle [root]. It keeps hair and hems from stretching under a hard jerk, which is most of
 * what makes cloth read as cloth.
 */
class LongRangeConstraints(val particle: IntArray, val root: IntArray, val maxDistance: FloatArray) {
    init { require(particle.size == root.size && root.size == maxDistance.size) }
    val size: Int get() = particle.size

    companion object {
        val Empty = LongRangeConstraints(IntArray(0), IntArray(0), FloatArray(0))
    }
}

/** Global settings of one solve. */
data class SimSettings(
    /** Gravity in px/s², world space (y up). */
    val gravityX: Float = 0f,
    val gravityY: Float = -980f,
    /** Uniform wind acceleration in px/s², scaled per particle by [SimState.windFactor]. */
    val windX: Float = 0f,
    val windY: Float = 0f,
    val substeps: Int = 16,
    /** Compliance of a pin with weight 1 is 0 (rigid); a softer pin scales this by (1 - w) / w. */
    val pinCompliance: Float = 1e-4f,
) {
    init {
        require(substeps in 1..128) { "Substeps must be within 1..128" }
        require(listOf(gravityX, gravityY, windX, windY, pinCompliance).all(Float::isFinite))
        require(pinCompliance >= 0f)
    }
}

/**
 * The solver. [pinWeight] and [goalStrength] are per particle (0 = none). A pin weight of 1 makes the
 * particle kinematic on its anchor; below that the pin is a spring.
 */
class XpbdSolver(
    val state: SimState,
    val stretch: DistanceConstraints = DistanceConstraints.Empty,
    val bend: DistanceConstraints = DistanceConstraints.Empty,
    val welds: WeldConstraints = WeldConstraints.Empty,
    val longRange: LongRangeConstraints = LongRangeConstraints.Empty,
    val pinWeight: FloatArray = FloatArray(state.count),
    /** Compliance toward the goal shape per particle; [Float.POSITIVE_INFINITY] or no entry is none. */
    val goalCompliance: FloatArray = FloatArray(state.count) { Float.POSITIVE_INFINITY },
    var settings: SimSettings = SimSettings(),
) {
    private val n = state.count
    private val stretchLambda = FloatArray(stretch.size)
    private val bendLambda = FloatArray(bend.size)
    private val weldLambda = FloatArray(welds.size * 2)
    private val pinLambda = FloatArray(n * 2)
    private val goalLambda = FloatArray(n * 2)

    init {
        require(pinWeight.size == n && goalCompliance.size == n) { "Per-particle arrays must match the particle count" }
    }

    /** Pinned particles are those with a full pin; they move only with their anchor. */
    private fun kinematic(i: Int) = pinWeight[i] >= 0.999f || state.invMass[i] == 0f

    /** Advances one frame of [dt] seconds. */
    fun step(dt: Float) {
        require(dt.isFinite() && dt > 0f) { "Frame time must be positive" }
        val s = state
        val substeps = settings.substeps
        val h = dt / substeps
        for (sub in 1..substeps) {
            val t = sub.toFloat() / substeps
            for (i in 0 until n) {
                s.stepAnchorX[i] = s.lastAnchorX[i] + (s.anchorX[i] - s.lastAnchorX[i]) * t
                s.stepAnchorY[i] = s.lastAnchorY[i] + (s.anchorY[i] - s.lastAnchorY[i]) * t
                s.stepGoalX[i] = s.lastGoalX[i] + (s.goalX[i] - s.lastGoalX[i]) * t
                s.stepGoalY[i] = s.lastGoalY[i] + (s.goalY[i] - s.lastGoalY[i]) * t
            }
            val angle = s.lastFrameAngle + (s.frameAngle - s.lastFrameAngle) * t
            integrate(h)
            stretchLambda.fill(0f); bendLambda.fill(0f); weldLambda.fill(0f); pinLambda.fill(0f); goalLambda.fill(0f)
            solvePins(h)
            solveDistances(stretch, stretchLambda, h)
            solveDistances(bend, bendLambda, h)
            solveWelds(h)
            solveGoals(h, angle)
            solveLongRange()
            updateVelocities(h)
        }
        s.settle()
    }

    private fun integrate(h: Float) {
        val s = state
        val g = settings
        for (i in 0 until n) {
            s.px[i] = s.x[i]; s.py[i] = s.y[i]
            if (kinematic(i)) {
                s.x[i] = s.stepAnchorX[i]; s.y[i] = s.stepAnchorY[i]
                continue
            }
            val decay = exp(-s.damping[i] * h)
            s.vx[i] = (s.vx[i] + (g.gravityX + g.windX * s.windFactor[i]) * h) * decay
            s.vy[i] = (s.vy[i] + (g.gravityY + g.windY * s.windFactor[i]) * h) * decay
            s.x[i] += s.vx[i] * h
            s.y[i] += s.vy[i] * h
        }
    }

    private fun solveDistances(c: DistanceConstraints, lambda: FloatArray, h: Float) {
        val s = state
        val h2 = h * h
        for (k in 0 until c.size) {
            val a = c.a[k]
            val b = c.b[k]
            val wa = if (kinematic(a)) 0f else s.invMass[a]
            val wb = if (kinematic(b)) 0f else s.invMass[b]
            val w = wa + wb
            if (w == 0f) continue
            val dx = s.x[a] - s.x[b]
            val dy = s.y[a] - s.y[b]
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1e-9f) continue
            val constraint = length - c.rest[k]
            val alpha = (if (constraint < 0f) c.compressionCompliance[k] else c.compliance[k]) / h2
            val delta = (-constraint - alpha * lambda[k]) / (w + alpha)
            lambda[k] += delta
            val nx = dx / length
            val ny = dy / length
            s.x[a] += wa * delta * nx; s.y[a] += wa * delta * ny
            s.x[b] -= wb * delta * nx; s.y[b] -= wb * delta * ny
        }
    }

    private fun solveWelds(h: Float) {
        val s = state
        val h2 = h * h
        for (k in 0 until welds.size) {
            val a = welds.a[k]
            val b = welds.b[k]
            // Each side's share of the pull, as Cubism weights it, limited by whether it can move at all.
            val wa = if (kinematic(a)) 0f else s.invMass[a] * welds.weightA[k]
            val wb = if (kinematic(b)) 0f else s.invMass[b] * welds.weightB[k]
            val w = wa + wb
            if (w == 0f) continue
            val alpha = welds.compliance[k] / h2
            for (axis in 0..1) {
                val offset = if (axis == 0) s.x[a] - s.x[b] else s.y[a] - s.y[b]
                val slot = k * 2 + axis
                val delta = (-offset - alpha * weldLambda[slot]) / (w + alpha)
                weldLambda[slot] += delta
                if (axis == 0) { s.x[a] += wa * delta; s.x[b] -= wb * delta } else { s.y[a] += wa * delta; s.y[b] -= wb * delta }
            }
        }
    }

    private fun solvePins(h: Float) {
        val s = state
        val h2 = h * h
        for (i in 0 until n) {
            val weight = pinWeight[i]
            if (weight <= 0f || kinematic(i)) continue
            val compliance = settings.pinCompliance * (1f - weight) / weight
            attach(i, s.stepAnchorX[i], s.stepAnchorY[i], compliance / h2, pinLambda)
        }
    }

    private fun solveGoals(h: Float, angle: Float) {
        val s = state
        val h2 = h * h
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        for (i in 0 until n) {
            val compliance = goalCompliance[i]
            if (!compliance.isFinite() || kinematic(i)) continue
            val ox = s.goalOffsetX[i] * cos - s.goalOffsetY[i] * sin
            val oy = s.goalOffsetX[i] * sin + s.goalOffsetY[i] * cos
            attach(i, s.stepGoalX[i] + ox, s.stepGoalY[i] + oy, compliance / h2, goalLambda)
        }
    }

    /** A zero-length spring from particle [i] to a moving point, one XPBD update per axis. */
    private fun attach(i: Int, tx: Float, ty: Float, alpha: Float, lambda: FloatArray) {
        val s = state
        val w = s.invMass[i]
        if (w == 0f) return
        val dxDelta = (-(s.x[i] - tx) - alpha * lambda[i * 2]) / (w + alpha)
        lambda[i * 2] += dxDelta
        s.x[i] += w * dxDelta
        val dyDelta = (-(s.y[i] - ty) - alpha * lambda[i * 2 + 1]) / (w + alpha)
        lambda[i * 2 + 1] += dyDelta
        s.y[i] += w * dyDelta
    }

    private fun solveLongRange() {
        val s = state
        for (k in 0 until longRange.size) {
            val i = longRange.particle[k]
            if (kinematic(i)) continue
            val r = longRange.root[k]
            val dx = s.x[i] - s.x[r]
            val dy = s.y[i] - s.y[r]
            val length = sqrt(dx * dx + dy * dy)
            val limit = longRange.maxDistance[k]
            if (length <= limit || length < 1e-9f) continue
            val k2 = limit / length
            s.x[i] = s.x[r] + dx * k2
            s.y[i] = s.y[r] + dy * k2
        }
    }

    private fun updateVelocities(h: Float) {
        val s = state
        for (i in 0 until n) {
            s.vx[i] = (s.x[i] - s.px[i]) / h
            s.vy[i] = (s.y[i] - s.py[i]) / h
        }
    }

    /** Largest relative stretch over [stretch] constraints, for tests and the bake report. */
    fun maxStretch(): Float {
        var worst = 0f
        for (k in 0 until stretch.size) {
            val dx = state.x[stretch.a[k]] - state.x[stretch.b[k]]
            val dy = state.y[stretch.a[k]] - state.y[stretch.b[k]]
            val rest = stretch.rest[k]
            if (rest > 1e-6f) worst = max(worst, sqrt(dx * dx + dy * dy) / rest - 1f)
        }
        return worst
    }
}
