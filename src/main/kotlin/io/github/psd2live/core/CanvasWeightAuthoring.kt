package io.github.psd2live.core

import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.*

/** Canvas weight tools share neutral point geometry and material arithmetic with every host. */
internal object CanvasWeightAuthoring {
    enum class Mode { ADD, SUBTRACT, SET, SMOOTH }

    fun apply(base: FloatArray, reach: FloatArray, mode: Mode, amount: Float,
              neighbors: List<IntArray>? = null): FloatArray {
        canvasWeightCheckpoint()
        require(amount.isFinite() && amount in 0f..1f && reach.all { it.isFinite() && it in 0f..1f })
        require(base.size == reach.size && base.all { it.isFinite() && it in 0f..1f })
        if (mode == Mode.SMOOTH) {
            var current = base.copyOf()
            requireNotNull(neighbors) { "Smoothing needs mesh adjacency" }
            repeat(4) {
                val next = current.copyOf()
                for (i in current.indices) {
                    if (i % 512 == 0) canvasWeightCheckpoint()
                    val adjacent = (neighbors.getOrNull(i) ?: intArrayOf()).filter { it in current.indices }
                    if (reach[i] > 0f && adjacent.isNotEmpty())
                        next[i] = current[i] + (adjacent.map { current[it] }.average().toFloat() - current[i]) * amount * reach[i]
                }
                current = next
            }
            return FloatArray(current.size) { current[it].coerceIn(0f, 1f) }
        }
        return FloatArray(base.size) { i ->
            if (i % 512 == 0) canvasWeightCheckpoint()
            when (mode) {
                Mode.ADD -> base[i] + amount * reach[i]
                Mode.SUBTRACT -> base[i] - amount * reach[i]
                Mode.SET -> base[i] + (amount - base[i]) * reach[i]
                Mode.SMOOTH -> error("Handled above")
            }.coerceIn(0f, 1f)
        }
    }

    fun gradient(points: List<CanvasBrushPoint>, from: CanvasBrushPoint, to: CanvasBrushPoint): FloatArray {
        val axis = to - from
        val length2 = axis.x * axis.x + axis.y * axis.y
        if (length2 < 1f) return FloatArray(points.size)
        return FloatArray(points.size) { i ->
            if (i % 512 == 0) canvasWeightCheckpoint()
            val d = points[i] - from
            (1f - (d.x * axis.x + d.y * axis.y) / length2).coerceIn(0f, 1f)
        }
    }

    fun invert(weights: FloatArray) = FloatArray(weights.size) {
        if (it % 512 == 0) canvasWeightCheckpoint()
        1f - weights[it]
    }

    fun group(model: PuppetModel, id: DrawableId, kind: VertexGroupKind, name: String? = null): VertexGroup? =
        model.vertexGroups.firstOrNull { it.drawableId == id && if (name == null) it.kind == kind else it.name == name }

    fun name(model: PuppetModel, id: DrawableId, kind: VertexGroupKind, requested: String? = null): String {
        requested?.let { require(it.isNotBlank() && it.none(Char::isISOControl)); return it }
        group(model, id, kind)?.let { return it.name }
        val taken = model.vertexGroups.filter { it.drawableId == id }.mapTo(HashSet()) { it.name }
        val stem = kind.jsonName
        return if (stem !in taken) stem else generateSequence(2) { it + 1 }.map { "$stem$it" }.first { it !in taken }
    }

    /** Matches the edit canvas: local key geometry through parents, in canvas pixels with Y down. */
    fun surfaces(model: PuppetModel, targets: List<DrawableId>, pose: Map<String, Float>, connected: Boolean): List<CanvasBrushSurface> {
        val defaults = model.parameters.associate { it.id to it.default }
        val worlds = buildDeformerWorlds(model.deformers,
            { id -> pose[id.raw] ?: defaults[id] ?: 0f }, { id -> defaults[id] ?: 0f })
        return targets.map { id ->
            canvasWeightCheckpoint()
            val drawable = model.drawables.singleOrNull { it.id == id } ?: error("Mesh not found: ${id.raw}")
            val mesh = requireNotNull(drawable.mesh) { "Mesh not found: ${id.raw}" }
            val local = RigGeometryTools.geometry(model, "mesh", id.raw, pose).points
            val world = DrawableSpaceMapping(drawable.parentDeformerId?.let { requireNotNull(worlds[it]) }).localToWorld(local)
            CanvasBrushSurface((world.indices step 2).map {
                if (it % 1024 == 0) canvasWeightCheckpoint()
                CanvasBrushPoint(world[it], -world[it + 1])
            },
                if (connected) org.umamo.edit.MeshTopology.buildVertexAdjacency(mesh.vertexCount, mesh.indices) else null,
                mesh.indices, id.raw.hashCode())
        }
    }
}
