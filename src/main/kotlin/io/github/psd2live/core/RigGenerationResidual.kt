package io.github.psd2live.core

import org.umamo.edit.VertexSource
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.*
import org.umamo.runtime.model.*

/** Mesh corrections stay local; automatic and authored ancestor interpolation remains in the hierarchy. */
internal object RigGenerationResidual {
    fun geometry(current: PuppetModel, previous: PuppetModel, desired: PuppetModel, id: DrawableId,
                 previousSources: List<VertexSource>, desiredSources: List<VertexSource>, checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm>? {
        val actual = current.drawables.single { it.id == id }
        val old = previous.drawables.single { it.id == id }; val next = desired.drawables.single { it.id == id }
        val mesh = requireNotNull(actual.mesh)
        val live = current.parameters.associateBy { it.id }
        val axes = unionAxes((actual.geometryGrid?.axes.orEmpty() + geometryAxes(previous, old) + geometryAxes(desired, next))
            .filter { it.parameterId in live })
        val mapping = requireNotNull(drawableSpaceMapping(current, emptyMap(), id))
        val indices = (0 until mesh.vertexCount).toSet()
        val coordinates = coordinates(axes, mesh.positions.size)
        val cells = coordinates.map { coordinate ->
            checkpoint()
            val pose = live.mapValues { it.value.default } + axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
            val before = transfer(world(previous, old, pose), previousSources)
            val after = transfer(world(desired, next, pose), desiredSources)
            val authored = local(current, actual, pose)
            val oldLocal = mapping.worldToLocalLinearized(before, mesh.positions, before, indices)
            val newLocal = mapping.worldToLocalLinearized(after, mesh.positions, after, indices)
            val reconstructed = mapping.localToWorld(newLocal)
            require(reconstructed.indices.all { kotlin.math.abs(reconstructed[it] - after[it]) <= 0.05f }) {
                "Generation migration cannot invert the authored neutral frame"
            }
            val local = FloatArray(authored.size) { authored[it] - oldLocal[it] + newLocal[it] }
            KeyformCell(coordinate, MeshDeltaForm(FloatArray(local.size) { local[it] - mesh.positions[it] }))
        }
        if (axes.isEmpty() && cells.single().form.positionDeltas.all { it == 0f }) return null
        return KeyformGrid(axes, cells)
    }

    fun channels(current: PuppetModel, actual: Drawable, previous: Drawable, desired: Drawable,
                 checkpoint: () -> Unit): ChannelGrids {
        val live = current.parameters.associateBy { it.id }
        val channelIds = actual.channelGrids.gridsByChannel.keys + previous.channelGrids.gridsByChannel.keys + desired.channelGrids.gridsByChannel.keys
        return ChannelGrids(channelIds.associateWith { channel ->
            val axes = unionAxes((actual.channelGrids[channel]?.axes.orEmpty() + previous.channelGrids[channel]?.axes.orEmpty() +
                desired.channelGrids[channel]?.axes.orEmpty()).filter { it.parameterId in live })
            val cells = coordinates(axes, 4).map { coordinate ->
                checkpoint()
                val pose = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
                fun parameter(d: Drawable, id: ParameterId): Float {
                    val value = pose[id] ?: live[id]?.default ?: 0f
                    // A generated switch may acquire a larger range. Project its old automatic
                    // contribution to the nearest old knot, including the authored residual on
                    // that contribution; falling back to static channels would discard that residual.
                    if (previous.channelGrids[channel]?.axes.orEmpty().any { it.parameterId == id }) {
                        val axis = d.channelGrids[channel]?.axes?.singleOrNull { it.parameterId == id }
                        if (axis != null) return value.coerceIn(axis.keys.first(), axis.keys.last())
                    }
                    return value
                }
                fun scalar(d: Drawable) = d.channelGrids.scalarAt(channel,
                    if (channel == FormChannel.DRAW_ORDER) d.drawOrder else d.opacity, paramValue = { parameter(d, it) })
                fun color(d: Drawable) = d.channelGrids.colorAt(channel,
                    if (channel == FormChannel.MULTIPLY_COLOR) d.multiplyColor else d.screenColor, paramValue = { parameter(d, it) })
                val value = when (channel.valueKind) {
                    ChannelValueKind.SCALAR -> ChannelValue.Scalar(scalar(actual) - scalar(previous) + scalar(desired))
                    ChannelValueKind.COLOR -> {
                        val a = color(actual); val b = color(previous); val c = color(desired)
                        ChannelValue.Color(ColorRgb(a.red - b.red + c.red, a.green - b.green + c.green, a.blue - b.blue + c.blue))
                    }
                    ChannelValueKind.FLAG -> {
                        val a = actual.channelGrids.flagAt(channel, false, paramValue = { parameter(actual, it) })
                        val b = previous.channelGrids.flagAt(channel, false, paramValue = { parameter(previous, it) })
                        ChannelValue.Flag(if (a != b) a else desired.channelGrids.flagAt(channel, false, paramValue = { parameter(desired, it) }))
                    }
                }
                KeyformCell(coordinate, value)
            }
            KeyformGrid(axes, cells)
        })
    }

    /** All current knot values survive; generated near-duplicates use the existing knot. */
    private fun unionAxes(input: List<KeyformAxis>): List<KeyformAxis> = input.groupBy { it.parameterId }.map { (id, axes) ->
        val values = mutableListOf<Float>()
        axes.forEach { axis -> axis.keys.forEach { value -> if (values.none { kotlin.math.abs(it - value) < EPS_KEY }) values += value } }
        KeyformAxis(id, values.sorted().toFloatArray())
    }

    private fun coordinates(axes: List<KeyformAxis>, components: Int): List<IntArray> {
        var count = 1L
        for (axis in axes) {
            require(axis.keys.isNotEmpty()) { "Generation migration has an empty axis" }
            count = Math.multiplyExact(count, axis.keys.size.toLong())
            require(count <= 262144 && count * components <= 16777216) { "Generation migration exceeds the keyform allocation limit" }
        }
        return (0 until count.toInt()).map { index ->
            var value = index
            IntArray(axes.size) { axis -> (value % axes[axis].keys.size).also { value /= axes[axis].keys.size } }
        }
    }

    private fun geometryAxes(model: PuppetModel, drawable: Drawable): List<KeyformAxis> {
        val axes = drawable.geometryGrid?.axes.orEmpty().toMutableList()
        var parent = drawable.parentDeformerId
        val seen = mutableSetOf<DeformerId>()
        while (parent != null) {
            require(seen.add(parent)) { "Generation migration parent hierarchy contains a cycle" }
            val deformer = requireNotNull(model.deformers.singleOrNull { it.id == parent }) { "Generation migration parent is missing" }
            axes += when (deformer) {
                is Deformer.Warp -> deformer.geometryGrid?.axes.orEmpty()
                is Deformer.Rotation -> deformer.geometryGrid?.axes.orEmpty()
            }
            parent = deformer.parent
        }
        return axes
    }

    private fun world(model: PuppetModel, drawable: Drawable, pose: Map<ParameterId, Float>): FloatArray {
        return requireNotNull(drawableSpaceMapping(model, pose, drawable.id)) { "Generation migration parent cannot be evaluated" }
            .localToWorld(local(model, drawable, pose))
    }

    private fun local(model: PuppetModel, drawable: Drawable, pose: Map<ParameterId, Float>): FloatArray {
        val mesh = requireNotNull(drawable.mesh)
        val local = mesh.positions.copyOf()
        val grid = drawable.geometryGrid
        if (grid != null) {
            val parameters = model.parameters.associateBy { it.id }
            val corners = requireNotNull(gridCorners(grid) { id ->
                val axis = grid.axes.single { it.parameterId == id }
                (pose[id] ?: parameters[id]?.default ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
            })
            for (corner in corners) {
                val values = grid.cellsByLinearIndex[corner.linearIndex]?.form?.positionDeltas ?: continue
                require(values.size == local.size) { "Generation migration has stale keyform dimensions" }
                for (index in values.indices) local[index] += values[index] * corner.weight
            }
        }
        return local
    }

    private fun transfer(values: FloatArray, sources: List<VertexSource>): FloatArray = FloatArray(sources.size * 2) { index ->
        val component = index % 2
        when (val source = sources[index / 2]) {
            is VertexSource.FromOld -> values[source.oldIndex * 2 + component]
            is VertexSource.BarycentricOf -> values[source.oldA * 2 + component] * source.wa + values[source.oldB * 2 + component] * source.wb +
                values[source.oldC * 2 + component] * source.wc
            else -> error("Unsupported generation migration vertex source")
        }
    }
}
