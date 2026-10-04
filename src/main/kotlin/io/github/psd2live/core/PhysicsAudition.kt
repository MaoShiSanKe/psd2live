package io.github.psd2live.core

/** Copied selected-group frame. No engine, mutable particle array or drag escapes the processor. */
internal data class PhysicsAuditionFrame(
    val setting: RigPhysicsEdit,
    val x: List<Float>,
    val y: List<Float>,
    val outputs: Map<String, Float>,
    val peaks: Map<Int, Float>,
    val dragX: Float,
    val dragY: Float,
    val serial: Long,
    val elapsed: Double,
) {
    val size: Int get() = x.size
}

/** Selected-group audition used by the panel and process-owned Agent sessions. */
internal class PhysicsAudition {
    private var engine: PhysicsEngine? = null
    private var drag = PhysicsDrag()
    private var ranges = emptyMap<String, PhysicsEngine.Range>()
    private var outputs = emptyMap<String, Float>()
    private var serial = 0L
    private var elapsed = 0.0

    @Synchronized fun configure(setting: RigPhysicsEdit, ranges: Map<String, PhysicsEngine.Range>, fps: Int) {
        require(fps in 0..240)
        require(ranges.values.all { it.min.isFinite() && it.max.isFinite() && it.default.isFinite() && it.min <= it.max })
        require(setting.parameters.all { it in ranges }) { "Audition parameter is not available" }
        if (engine?.strands?.singleOrNull()?.setting == setting && this.ranges == ranges && engine?.fps == fps.toFloat()) return
        engine = PhysicsEngine(listOf(setting), ranges.toMap(), fps.toFloat()).also { it.carryOver(engine) }
        this.ranges = ranges.toMap()
        outputs = outputs.filterKeys { it in setting.outputParameters }
    }

    @Synchronized fun target(x: Float, y: Float) {
        require(x.isFinite() && y.isFinite() && x in -1f..1f && y in -1f..1f) { "Drag target must be within -1..1" }
        drag.target(x, y)
    }

    @Synchronized fun release() = drag.release()

    @Synchronized fun reset() {
        engine?.reset(); drag.reset(); outputs = emptyMap(); serial = 0L; elapsed = 0.0
    }

    @Synchronized fun resetPeaks() { engine?.strands?.forEach { it.resetPeaks() } }

    @Synchronized fun frame(): PhysicsAuditionFrame {
        val strand = requireNotNull(engine?.strands?.singleOrNull()) { "Configure an audition first" }
        return PhysicsAuditionFrame(strand.setting, strand.x.toList(), strand.y.toList(), outputs.toMap(),
            strand.setting.outputs.indices.mapNotNull { index -> ranges[strand.setting.outputs[index].parameter]
                ?.let { index to strand.peakFraction(index, it) } }.toMap(),
            drag.x, drag.y, serial, elapsed)
    }

    /** Compute all steps on copied state; a rejected or cancelled solve publishes no prefix. */
    @Synchronized fun step(values: Map<String, Float>, dt: Float, steps: Int = 1, check: () -> Unit = {}): PhysicsAuditionFrame {
        require(dt.isFinite() && dt > 0f && dt <= 0.1f && steps in 1..240) { "Use dt within (0,0.1] and 1..240 steps" }
        require(values.all { (id, value) -> ranges[id]?.let { value.isFinite() && value in it.min..it.max } == true }) {
            "Audition values must identify an available parameter within its range"
        }
        val before = requireNotNull(engine) { "Configure an audition first" }
        val setting = before.strands.single().setting
        val candidate = PhysicsEngine(listOf(setting), ranges, before.fps).also { it.carryOver(before) }
        val candidateDrag = drag.copy()
        var candidateOutputs = outputs
        repeat(steps) {
            check()
            candidateDrag.update(dt)
            candidateOutputs = candidate.step(candidateDrag.apply(values, setting.inputs.map { it.parameter }, ranges), dt)
        }
        check()
        engine = candidate; drag = candidateDrag; outputs = candidateOutputs
        serial += steps; elapsed += dt.toDouble() * steps
        return frame()
    }
}
