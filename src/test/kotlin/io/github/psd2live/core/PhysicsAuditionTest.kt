package io.github.psd2live.core

import kotlinx.coroutines.CancellationException
import kotlin.test.*

class PhysicsAuditionTest {
    private val ranges = mapOf("ParamAngleX" to PhysicsEngine.Range(-30f, 30f, 0f),
        "Tail" to PhysicsEngine.Range(-1f, 1f, 0f))
    private val setting = RigPhysicsEdit("test", "Test", listOf(PhysicsInput("ParamAngleX", 100f, PhysicsSourceType.X)),
        listOf(PhysicsOutput("Tail", 1, 1.5f)), listOf(PhysicsSegment(5f, 0.9f, 0.9f, 1.2f)))
    private fun audition() = PhysicsAudition().also { it.configure(setting, ranges, 60) }

    @Test fun explicitStepsAndDragMatchThePanelEngineAndFramesRemainCopied() {
        val session = audition()
        val initial = session.frame()
        session.target(0.8f, 0f)
        val engine = PhysicsEngine(listOf(setting), ranges, 60f)
        val drag = PhysicsDrag().also { it.target(0.8f, 0f) }
        val values = mapOf("ParamAngleX" to 15f)
        var expected = emptyMap<String, Float>()
        repeat(120) { drag.update(1f / 60); expected = engine.step(drag.apply(values, setting.inputs.map { it.parameter }, ranges), 1f / 60) }
        val frame = session.step(values, 1f / 60, 120)
        assertEquals(expected, frame.outputs)
        assertEquals(engine.strands.single().x.toList(), frame.x)
        assertEquals(engine.strands.single().y.toList(), frame.y)
        assertEquals(120L, frame.serial)
        assertTrue(frame.peaks.getValue(0) > 0f)
        assertEquals(listOf(0f, 0f), initial.x)
        assertEquals(0L, initial.serial)
        assertEquals(frame, session.frame(), "Reading must not advance the audition")
    }

    @Test fun cancelledAndInvalidStepsPublishNeitherParticlesDragNorClock() {
        val session = audition().also { it.target(0.7f, -0.2f) }
        val before = session.frame()
        var calls = 0
        assertFailsWith<CancellationException> {
            session.step(emptyMap(), 1f / 60, 30) { if (++calls == 7) throw CancellationException("cancel") }
        }
        assertEquals(before, session.frame())
        assertFailsWith<IllegalArgumentException> { session.step(mapOf("Missing" to 1f), 1f / 60) }
        assertFailsWith<IllegalArgumentException> { session.step(mapOf("ParamAngleX" to 31f), 1f / 60) }
        assertFailsWith<IllegalArgumentException> { session.target(Float.NaN, 0f) }
        assertEquals(before, session.frame())
        val clean = audition().also { it.target(0.7f, -0.2f) }
        assertEquals(clean.step(emptyMap(), 1f / 60, 30), session.step(emptyMap(), 1f / 60, 30))
    }

    @Test fun retuningPreservesMotionAndPeakResetDoesNotResetTheScene() {
        val session = audition().also { it.target(1f, 0f) }
        val old = session.step(emptyMap(), 1f / 60, 60)
        val next = setting.copy(outputs = setting.outputs.map { it.copy(scale = 2f) })
        session.configure(next, ranges, 60)
        val tuned = session.frame()
        assertEquals(old.x, tuned.x); assertEquals(old.y, tuned.y)
        assertEquals(old.elapsed, tuned.elapsed); assertEquals(old.serial, tuned.serial)
        assertEquals(next, tuned.setting)
        session.resetPeaks()
        val cleared = session.frame()
        assertEquals(tuned.x, cleared.x); assertEquals(tuned.outputs, cleared.outputs)
        assertTrue(cleared.peaks.values.all { it == 0f })
        session.release(); session.reset()
        val reset = session.frame()
        assertEquals(0L, reset.serial); assertEquals(0.0, reset.elapsed)
        assertEquals(0f, reset.dragX); assertEquals(0f, reset.dragY)
        assertEquals(listOf(0f, 0f), reset.x)
    }
}
