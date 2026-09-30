package io.github.psd2live.core.sim

import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.SwingAuthoring
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterKeys
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/**
 * Writes baked simulations back onto a rebuilt rig, after the swings: adds the static corrections to the
 * target meshes' keyforms on the static parameters' axes, and creates each mode's parameter with its key
 * shapes - as blend shapes where the runtime has them and the simulation asks for them, which add to the
 * grid instead of multiplying it, otherwise as another keyform axis. Nothing is simulated here. A bake that no longer fits (a mesh gone or remeshed, a static parameter deleted) is skipped where
 * it does not fit and reported by [issues] instead of failing the rebuild.
 */
object SimGenerator {
    /** Past this many cells a mesh's keyform grid is left alone. */
    private const val MAX_CELLS = 200_000
    /** Past this many keyforms on a target, a simulation left to choose writes its modes as blend shapes. */
    const val AUTO_BLEND_CELLS = 64L

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

    /**
     * Whether [sim]'s modes go in as blend shapes on [model]. Keyform axes play the same and every Cubism
     * version reads them, so left to choose, blend shapes are used only where the axes would multiply a
     * target's keyforms past [AUTO_BLEND_CELLS].
     */
    fun usesBlendShapes(model: PuppetModel, sim: RigSimEdit): Boolean {
        if (!model.runtimeTarget.supports(RuntimeFeature.MeshWarpBlendShapes)) return false
        sim.blendShapes?.let { return it }
        val modes = sim.bake?.modes?.map { it.axis.keys.size } ?: List(sim.modes) { sim.keys }
        val own = modes.indices.map { ParameterId(parameterId(sim, it + 1)) }.toSet()
        val added = modes.fold(1L) { n, keys -> n * keys }
        return sim.targets.any { target ->
            val grid = model.drawables.firstOrNull { it.id.raw == target }?.geometryGrid
            val cells = grid?.axes?.filter { it.parameterId !in own }?.fold(1L) { n, axis -> n * axis.keys.size } ?: 1L
            cells * added > AUTO_BLEND_CELLS
        }
    }

    fun applyOne(model: PuppetModel, sim: RigSimEdit): Pair<PuppetModel, List<String>> {
        val bake = sim.bake?.takeIf { sim.enabled } ?: return model to emptyList()
        val issues = ArrayList<String>()
        var current = model
        val blend = usesBlendShapes(model, sim)
        for ((k, mode) in bake.modes.withIndex()) {
            val id = ParameterId(mode.axis.parameter)
            if (current.parameters.any { it.id == id }) continue
            val name = if (bake.modes.size == 1) sim.name else "${sim.name} ${k + 1}"
            current = current.withParameterCreated(id, name, if (blend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL)
            if (blend) current = current.copy(parameters = current.parameters.map {
                if (it.id == id) it.copy(min = mode.axis.keys.first(), max = mode.axis.keys.last(), default = 0f, keys = mode.axis.keys.toList()) else it
            })
        }
        // Static corrections first: blend shapes add to the grid as it is at the default pose.
        for (axis in bake.statics + bake.modes.map { it.axis }) {
            val parameter = current.parameters.firstOrNull { it.id.raw == axis.parameter }
            if (parameter == null) { issues += "${sim.id}: parameter ${axis.parameter} no longer exists"; continue }
            val asBlend = blend && axis !in bake.statics
            if (parameter.kind != (if (asBlend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL)) {
                issues += "${sim.id}: ${axis.parameter} is not a ${if (asBlend) "blend shape" else "normal"} parameter"; continue
            }
            for ((mesh, count) in bake.vertexCounts) {
                val offsets = axis.offsets[mesh] ?: continue
                val drawable = current.drawables.firstOrNull { it.id.raw == mesh }
                if (drawable?.mesh == null) { issues += "${sim.id}: mesh $mesh not found"; continue }
                if (drawable.mesh.vertexCount != count || offsets.any { it.size != count * 2 }) {
                    issues += "${sim.id}: $mesh was remeshed since the bake"; continue
                }
                val next = if (asBlend) drawable.copy(blendShapes = drawable.blendShapes.filter { it.parameterId != parameter.id } +
                    blendBinding(current, drawable, parameter.id, axis, mesh))
                else {
                    val grid = drawable.geometryGrid ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(count * 2)))))
                    val cells = withOffsets(grid, parameter, axis, mesh)?.takeIf { it.cells.size <= MAX_CELLS }
                    if (cells == null) { issues += "${sim.id}: $mesh keyforms cannot take ${axis.parameter}"; continue }
                    drawable.copy(geometryGrid = cells)
                }
                current = current.copy(drawables = current.drawables.map { if (it.id == drawable.id) next else it })
            }
            val authored = current.parameters.single { it.id == parameter.id }.keys
            if (authored != null) current = current.withParameterKeys(parameter.id, (authored + axis.keys.toList()).distinct().sorted())
        }
        return current to issues.distinct()
    }

    /**
     * [axis]'s offsets as a blend shape on [drawable]: each key's form is the grid at the default pose plus
     * the offsets, with the channels as they are there, so only the shape moves.
     */
    private fun blendBinding(model: PuppetModel, drawable: Drawable, parameter: ParameterId, axis: SimBakedAxis, mesh: String): BlendShapeBinding<MeshForm> {
        val defaults = model.parameters.associate { it.id to it.default }
        val defaultValue: (ParameterId) -> Float = { defaults[it] ?: 0f }
        val count = drawable.mesh!!.vertexCount * 2
        val reference = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(count)
        val drawOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder.toFloat(), defaultValue)
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue)
        val neutral = axis.keys.indexOfFirst { it == 0f }
        return BlendShapeBinding(parameter, axis.keys.copyOf(), neutral, axis.keys.indices.map { k ->
            if (k == neutral) null else {
                val add = axis.offsets.getValue(mesh)[k]
                MeshForm(FloatArray(count) { reference[it] + add[it] }, drawOrder, opacity, multiply, screen)
            }
        })
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
