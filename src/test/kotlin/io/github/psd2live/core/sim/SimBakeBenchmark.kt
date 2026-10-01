package io.github.psd2live.core.sim

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Bakes the tml example's back hair (top tenth pinned) at a few settings and plays the reference
 * simulation and the exported model (pendulum + keys) side by side on motion neither the fit nor its
 * held-out check saw: how closely the baked model follows, whether its parameters stall at ±1, and how
 * smooth it is. Slow, so it runs only with PSD2LIVE_BENCH=1; BAKE_CONFIGS picks settings as
 * modes:keys,... (default 2:5,2:7,1:5). Results go to standard output.
 */
class SimBakeBenchmark {
    @Test fun backHair() {
        assumeTrue(System.getenv("PSD2LIVE_BENCH") == "1", "Set PSD2LIVE_BENCH=1 to run the bake benchmark")
        val initial = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
        val puppet = initial.rig.puppet
        val layers = initial.analysis.layers.associateBy { it.source.id.raw }
        val back = puppet.drawables.first { d ->
            d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR
        }
        val world = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
        val ys = (1 until world.size step 2).map { world[it] }
        val top = ys.max(); val bottom = ys.min()
        val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN, FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
        val overlay = initial.config.rigEdits.copy(authoringJournal = initial.config.rigEdits.authoringJournal + VertexGroupJournal.encode(pin))
        val grouped = overlay.applyTo(initial.baseRig.puppet)
        val configs = (System.getenv("BAKE_CONFIGS") ?: "2:5,2:7,1:5").split(",").map { c -> c.split(":").map(String::toInt) }
        for ((modes, keys) in configs) {
            val o = SimAuthoring.put(overlay, grouped, RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = modes, keys = keys, exaggeration = 1f))
            val model = SimAuthoring.unbakedModel(o, initial.baseRig.puppet, "back")
            val edit = o.simEdits.single()
            val t = System.nanoTime()
            val bake = SimBaker.bake(model, edit)
            println("=== $modes modes, $keys keys: %.1f s; held-out R² %.3f, p95 %.1f px, peak %.2f, clipped %.3f, jerk ×%.2f".format(
                (System.nanoTime() - t) / 1e9, bake.fit, bake.maxErrorPx, bake.peak, bake.clipped, bake.jerk))
            println("    ${bake.physics}")
            for (extra in bake.extraPhysics) println("    own pendulum $extra")
            val written = SimAuthoring.withBake(o, "back", bake).applyTo(initial.baseRig.puppet)
            for (mode in bake.modes) println("    %s: swing %.1f px, %.0f%% of the motion; %s".format(mode.axis.parameter, mode.amplitude, mode.energy * 100,
                strain(model, edit, written, back.id, mode.axis.parameter)))
            for (blend in listOf(false, true)) {
                val written = o.copy(simEdits = o.simEdits.map { it.copy(blendShapes = blend) })
                val baked = SimAuthoring.withBake(written, "back", bake).applyTo(initial.baseRig.puppet)
                println("  as ${if (blend) "blend shapes" else "keyform axes"}")
                for (motion in Motion.entries) println("    %-6s %s".format(motion.name.lowercase(), play(model, edit, baked, bake, back.id, motion)))
            }
        }
    }

    /**
     * How much a mode bunches the body up: the edges along the grain, with the mode at either end of its
     * range against rest - their mean shortening and the share shortened by more than 2%.
     */
    private fun strain(model: PuppetModel, edit: RigSimEdit, baked: PuppetModel, id: DrawableId, parameter: String): String {
        val scene = SimScene.build(model, edit)
        val stretch = scene.solver.stretch
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(baked, emptyMap()).worldPositions.getValue(id)
        val offset = scene.offsets.getValue(id)
        val along = (0 until stretch.size).filter { scene.edgeAlong[it] > 0.5f }
        fun length(p: FloatArray, k: Int): Float {
            val a = stretch.a[k] - offset; val b = stretch.b[k] - offset
            return kotlin.math.hypot(p[a * 2] - p[b * 2], p[a * 2 + 1] - p[b * 2 + 1])
        }
        return listOf(-1f, 1f).joinToString(", ") { end ->
            val posed = evaluator.evaluate(baked, mapOf(ParameterId(parameter) to end * SimGenerator.MODE_RANGE)).worldPositions.getValue(id)
            val strains = along.map { k -> length(posed, k) / length(rest, k).coerceAtLeast(1e-3f) - 1f }
            val shortened = strains.filter { it < 0f }
            "at %+.0f: shortening %.1f%%, %.0f%% of grain edges past 2%%".format(end, -shortened.average().takeIf { !it.isNaN() }.let { it ?: 0.0 } * 100,
                strains.count { it < -0.02f } * 100.0 / strains.size.coerceAtLeast(1))
        }
    }

    /** Head and body motion unlike the bake's own: turns and wobbles, mouse drags, and steady hard shaking. */
    private enum class Motion { TURNS, DRAGS, SHAKE }

    private val names = listOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleZ")
    private val spans = listOf(30f, 30f, 10f, 10f)

    private fun track(motion: Motion, frames: Int): List<FloatArray> {
        fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val fade = { f: Int -> ease(f / 30f) * ease((frames - 60 - f) / 30f) }
        return when (motion) {
            Motion.TURNS -> names.indices.map { i ->
                FloatArray(frames) { f ->
                    val s = f / 60.0
                    when (i) {
                        0 -> 30f * (ease((f - 20) / 15f) - ease((f - 140) / 15f)) +
                            if (f > 220) (12 * sin(2 * PI * 0.8 * s) + 8 * sin(2 * PI * 1.3 * s + 1)).toFloat() * fade(f - 220) else 0f
                        1, 3 -> if (f > 220) (6 * sin(2 * PI * 0.6 * s + 2)).toFloat() * fade(f - 220) else 0f
                        else -> 0f
                    }
                }
            }
            Motion.DRAGS -> {
                val random = java.util.Random(5L)
                names.indices.map { i ->
                    val out = FloatArray(frames); var from = 0f; var to = 0f; var at = 0; var next = 10
                    for (f in 0 until frames) {
                        if (f == next && f < frames - 90) { from = out[f - 1]; to = (random.nextFloat() * 2 - 1) * spans[i]; at = f; next = f + 24 + random.nextInt(48) }
                        if (f >= frames - 90 && next >= 0) { from = out[f - 1]; to = 0f; at = f; next = -1 }
                        out[f] = from + (to - from) * ease((f - at) / 8f)
                    }
                    out
                }
            }
            Motion.SHAKE -> names.indices.map { i ->
                FloatArray(frames) { f -> if (i == 0) (spans[i] * sin(2 * PI * 1.2 * f / 60.0)).toFloat() * fade(f) else 0f }
            }
        }
    }

    /** The simulation against the baked model over [motion], world px over the target. */
    private fun play(model: PuppetModel, edit: RigSimEdit, baked: PuppetModel, bake: SimBakeResult, id: DrawableId, motion: Motion): String {
        val scene = SimScene.build(model, edit).also { it.calibrate(model); it.reset(model, emptyMap()) }
        val engine = PhysicsEngine(listOfNotNull(bake.physics), PhysicsEngine.ranges(baked.parameters))
        val evaluator = CpuDeformationEvaluator()
        val frames = 600
        val track = track(motion, frames)
        val simulated = ArrayList<FloatArray>(); val played = ArrayList<FloatArray>(); val parameters = ArrayList<FloatArray>()
        var clipped = 0; var peak = 0f
        for (f in 0 until frames) {
            val pose = names.indices.associate { ParameterId(names[it]) to track[it][f] }
            scene.drive(model, pose, 1f / 60f)
            val driven = engine.step(pose.mapKeys { it.key.raw }, 1f / 60f)
            if (driven.values.any { abs(it) >= 0.999f * SimGenerator.MODE_RANGE }) clipped++
            parameters += bake.parameters.map { driven[it] ?: 0f }.toFloatArray()
            peak = maxOf(peak, (driven.values.maxOfOrNull { abs(it) } ?: 0f) / SimGenerator.MODE_RANGE)
            val out = evaluator.evaluate(baked, pose + driven.mapKeys { ParameterId(it.key) }).worldPositions.getValue(id)
            val rig = evaluator.evaluate(model, pose).worldPositions.getValue(id)
            val offset = scene.offsets.getValue(id)
            simulated += FloatArray(rig.size) { k -> (if (k % 2 == 0) scene.state.x[offset + k / 2] else scene.state.y[offset + k / 2]) - rig[k] }
            played += FloatArray(rig.size) { k -> out[k] - rig[k] }
        }
        fun energy(rows: List<FloatArray>, order: Int): Double {
            var cur = rows
            repeat(order) { cur = cur.zipWithNext { a, b -> FloatArray(a.size) { b[it] - a[it] } } }
            return cur.sumOf { r -> r.sumOf { (it * it).toDouble() } }
        }
        val error = simulated.indices.sumOf { f -> simulated[f].indices.sumOf { k -> ((simulated[f][k] - played[f][k]) * (simulated[f][k] - played[f][k])).toDouble() } }
        val total = energy(simulated, 0)
        val count = frames * simulated.first().size / 2
        // How rough each signal is for how much it moves: third differences against first.
        fun rough(rows: List<FloatArray>) = sqrt(energy(rows, 3) / energy(rows, 1).coerceAtLeast(1e-12))
        return "R² %.3f, error %.1f px of %.1f px RMS, swing ×%.2f, jerk ×%.2f, peak %.2f, clipped %d frames; roughness sim %.3f baked %.3f parameters %.3f".format(
            1 - error / total, sqrt(error / count), sqrt(total / count), sqrt(energy(played, 0) / total),
            sqrt(energy(played, 3) / energy(simulated, 3)), peak, clipped, rough(simulated), rough(played), rough(parameters))
    }
}
