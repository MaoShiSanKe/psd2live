package io.github.psd2live.core.sim

import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.SwingAuthoring
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterKeys
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.model.*

/**
 * Writes baked simulations back onto a rebuilt rig, after the swings: creates each mode's parameter and
 * adds the baked offsets to the target meshes' keyforms on the mode and static axes. Nothing is simulated
 * here. A bake that no longer fits (a mesh gone or remeshed, a static parameter deleted) is skipped where
 * it does not fit and reported by [issues] instead of failing the rebuild.
 */
object SimGenerator {
    /** Past this many cells a mesh's keyform grid is left alone. */
    private const val MAX_CELLS = 200_000

    fun apply(model: PuppetModel, sims: List<RigSimEdit>): PuppetModel =
        sims.fold(model) { current, sim -> applyOne(current, sim).first }

    fun issues(model: PuppetModel, sim: RigSimEdit): List<String> = applyOne(model, sim).second

    /** `ParamSim<id>_<k>` for mode [k] (1-based). */
    fun parameterId(sim: RigSimEdit, k: Int) = "ParamSim${SwingAuthoring.asciiStem(sim.id)}_$k"

    /** `PhysicsSim_<id>`: the one pendulum of a baked simulation. */
    fun physicsId(sim: RigSimEdit) = "PhysicsSim_${sim.id}"

    /** The pendulum of every enabled baked simulation whose parameters exist. */
    fun physicsRules(sims: List<RigSimEdit>, available: Set<String>): List<RigPhysicsEdit> = sims.mapNotNull { sim ->
        val rule = sim.bake?.physics?.takeIf { sim.enabled } ?: return@mapNotNull null
        rule.copy(inputs = rule.inputs.filter { it.parameter in available }, outputs = rule.outputs.filter { it.parameter in available })
            .takeIf { it.inputs.isNotEmpty() && it.outputs.isNotEmpty() }
    }

    /** The simulation a generated pendulum belongs to. */
    fun simulationOf(groupId: String, sims: List<RigSimEdit>): RigSimEdit? = sims.firstOrNull { it.bake?.physics?.id == groupId }

    /** [pose] without the parameters [sim]'s bake drives, so they sit at their defaults under the live simulation. */
    fun withoutModes(pose: Map<ParameterId, Float>, sim: RigSimEdit): Map<ParameterId, Float> {
        val owned = sim.bake?.parameters?.toSet() ?: return pose
        return pose.filterKeys { it.raw !in owned }
    }

    fun applyOne(model: PuppetModel, sim: RigSimEdit): Pair<PuppetModel, List<String>> {
        val bake = sim.bake?.takeIf { sim.enabled } ?: return model to emptyList()
        val issues = ArrayList<String>()
        var current = model
        for ((k, mode) in bake.modes.withIndex()) {
            val id = ParameterId(mode.axis.parameter)
            current = current.withParameterCreated(id, if (bake.modes.size == 1) sim.name else "${sim.name} ${k + 1}")
        }
        val axes = bake.statics + bake.modes.map { it.axis }
        for (axis in axes) {
            val parameter = current.parameters.firstOrNull { it.id.raw == axis.parameter }
            if (parameter == null) { issues += "${sim.id}: parameter ${axis.parameter} no longer exists"; continue }
            if (parameter.kind != ParameterKind.NORMAL) { issues += "${sim.id}: ${axis.parameter} is not a normal parameter"; continue }
            for ((mesh, count) in bake.vertexCounts) {
                val offsets = axis.offsets[mesh] ?: continue
                val drawable = current.drawables.firstOrNull { it.id.raw == mesh }
                if (drawable?.mesh == null) { issues += "${sim.id}: mesh $mesh not found"; continue }
                if (drawable.mesh.vertexCount != count || offsets.any { it.size != count * 2 }) {
                    issues += "${sim.id}: $mesh was remeshed since the bake"; continue
                }
                val grid = drawable.geometryGrid ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(count * 2)))))
                val next = withOffsets(grid, parameter, axis, mesh)?.takeIf { it.cells.size <= MAX_CELLS }
                if (next == null) { issues += "${sim.id}: $mesh keyforms cannot take ${axis.parameter}"; continue }
                current = current.copy(drawables = current.drawables.map { if (it.id == drawable.id) it.copy(geometryGrid = next) else it })
            }
            val authored = current.parameters.single { it.id == parameter.id }.keys
            if (authored != null) current = current.withParameterKeys(parameter.id, (authored + axis.keys.toList()).distinct().sorted())
        }
        return current to issues.distinct()
    }

    /**
     * [grid] plus [axis]'s offsets for the mesh: a new axis gets the baked keys, each a copy of the grid with
     * its offsets added; an axis the grid already has gains the baked keys and every cell adds the offsets
     * at its key, so what was keyed there before is kept.
     */
    internal fun withOffsets(grid: KeyformGrid<MeshDeltaForm>, parameter: Parameter, axis: SimBakedAxis, mesh: String): KeyformGrid<MeshDeltaForm>? {
        if (!grid.isDense) return null
        val count = grid.cells.first().form.positionDeltas.size
        var next = grid
        var index = next.axisIndexOf(parameter.id)
        if (index < 0) {
            val cells = ArrayList<KeyformCell<MeshDeltaForm>>(next.cells.size * axis.keys.size)
            for (k in axis.keys.indices) for (cell in next.cells) cells += KeyformCell(cell.coordinate + k, cell.form)
            next = KeyformGrid(next.axes + KeyformAxis(parameter.id, axis.keys.copyOf()), cells)
            index = next.axes.size - 1
        } else {
            for (key in axis.keys) next = next.withKeyInserted(parameter.id, key, MeshDeltaInterpolator)
        }
        val keys = next.axes[index].keys
        return KeyformGrid(next.axes, next.cells.map { cell ->
            val add = axis.at(mesh, keys[cell.coordinate[index]]) ?: return null
            if (add.size != count) return null
            val base = cell.form.positionDeltas
            KeyformCell(cell.coordinate, MeshDeltaForm(FloatArray(count) { base[it] + add[it] }))
        })
    }
}
