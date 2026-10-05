package io.github.psd2live.core.sim

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/** One simulated frame for the canvas: the simulated meshes' world vertices, replacing the rig's. */
class SimulatedFrame(val simulationId: String, val positions: Map<DrawableId, FloatArray>, val serial: Long)

/**
 * The live simulation behind the preview. A scene is built and calibrated off the frame thread whenever the
 * rig or the edit changes ([prepare]); until it is ready [step] returns null and the preview shows the rig.
 */
class SimPreview {
    private class Prepared(val model: PuppetModel, val edit: RigSimEdit, val scene: SimScene)

    @Volatile private var prepared: Prepared? = null
    @Volatile private var pending: Pair<PuppetModel, RigSimEdit>? = null
    /** The last rig and edit that failed to prepare; not retried until either changes. */
    @Volatile private var failed: Pair<PuppetModel, RigSimEdit>? = null
    private var serial = 0L

    /** Whether [prepare] has to run for this rig and edit. */
    fun needs(model: PuppetModel, edit: RigSimEdit): Boolean {
        val ready = prepared
        val waiting = pending
        val broken = failed
        return !(ready != null && ready.model === model && ready.edit == edit) && !(waiting != null && waiting.first === model && waiting.second == edit) &&
            !(broken != null && broken.first === model && broken.second == edit)
    }

    /** Marks [model] and [edit] as being prepared, on the frame thread, so [needs] stops asking at once. */
    fun begin(model: PuppetModel, edit: RigSimEdit) {
        pending = model to edit
    }

    /**
     * Builds and calibrates the scene; slow, so call it off the frame thread. The scene starts at rest at
     * [pose]. Returns the setup notes, or throws what the edit got wrong.
     */
    fun prepare(model: PuppetModel, edit: RigSimEdit, pose: Map<ParameterId, Float>, progress: (Float) -> Unit = {},
                cancelled: () -> Boolean = { false }): List<String> {
        pending = model to edit
        try {
            val checkpoint = { if (cancelled()) throw java.util.concurrent.CancellationException("Simulation preparation cancelled") }
            val scene = SimScene.build(model, edit, checkpoint = checkpoint)
            scene.calibrate(model, progress = progress, cancelled = cancelled)
            checkpoint()
            scene.reset(model, pose)
            checkpoint()
            if (pending?.let { it.first === model && it.second == edit } == true) prepared = Prepared(model, edit, scene)
            return scene.notes
        } catch (failure: Exception) {
            failed = model to edit
            throw failure
        } finally {
            if (pending?.let { it.first === model && it.second == edit } == true) pending = null
        }
    }

    /** Advances [dt] at [pose] and returns the frame, or null while nothing is ready for this rig and edit. */
    fun step(model: PuppetModel, edit: RigSimEdit, pose: Map<ParameterId, Float>, dt: Float, checkpoint: () -> Unit = {}): SimulatedFrame? {
        val ready = prepared ?: return null
        if (ready.model !== model || ready.edit != edit) return null
        if (!ready.scene.drive(model, pose, dt, checkpoint)) return null
        return SimulatedFrame(edit.id, SimAuthoring.positions(ready.scene), ++serial)
    }

    /** Puts the running scene back at rest at [pose]. */
    fun restart(model: PuppetModel, pose: Map<ParameterId, Float>) {
        val ready = prepared?.takeIf { it.model === model } ?: return
        ready.scene.reset(model, pose)
        serial++
    }

    internal class Snapshot(val scene: SimScene.Snapshot, val serial: Long)
    internal fun snapshot(): Snapshot = Snapshot(requireNotNull(prepared).scene.snapshot(), serial)
    internal fun restore(snapshot: Snapshot) { requireNotNull(prepared).scene.restore(snapshot.scene); serial = snapshot.serial }
    internal fun frame(): SimulatedFrame {
        val ready = requireNotNull(prepared)
        return SimulatedFrame(ready.edit.id, SimAuthoring.positions(ready.scene), serial)
    }

    fun clear() {
        prepared = null
        pending = null
        failed = null
    }
}
