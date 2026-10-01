package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/** Authored samples in rest canvas space. Triangles interpolate new/refined vertices without index assumptions. */
data class SkeletonWeightMap(val positions: List<Float>, val triangles: List<Int>, val weights: List<Map<String, Float>>) {
    init {
        require(positions.size % 2 == 0 && positions.all(Float::isFinite) && weights.size * 2 == positions.size)
        require(triangles.size % 3 == 0 && triangles.all { it in weights.indices })
        require(weights.all { row -> row.all { (id, w) -> id.isNotBlank() && w.isFinite() && w >= 0f } })
    }
    private val lookup by lazy { weights.indices.associateBy { positions[it * 2] to positions[it * 2 + 1] } }

    fun sample(x: Float, y: Float): Map<String, Float>? {
        lookup[x to y]?.let { return weights[it] }
        for (i in triangles.indices step 3) {
            val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
            val ax = positions[a * 2]; val ay = positions[a * 2 + 1]
            val bx = positions[b * 2]; val by = positions[b * 2 + 1]
            val cx = positions[c * 2]; val cy = positions[c * 2 + 1]
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (kotlin.math.abs(det) < 1e-8f) continue
            val u = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
            val v = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
            val w = 1f - u - v
            if (minOf(u, v, w) < -0.0001f) continue
            val result = mutableMapOf<String, Float>()
            for ((vertex, factor) in listOf(a to u, b to v, c to w)) for ((id, value) in weights[vertex])
                result[id] = (result[id] ?: 0f) + value * factor.coerceAtLeast(0f)
            return result
        }
        return null
    }

    fun remapBones(mapping: Map<String, String?>): SkeletonWeightMap = copy(weights = weights.map { row ->
        val next = mutableMapOf<String, Float>()
        for ((id, w) in row) {
            val target = if (mapping.containsKey(id)) mapping[id] else id
            if (target != null) next[target] = (next[target] ?: 0f) + w
        }
        next.toMap()
    })

    fun toJson() = buildJsonObject {
        putJsonArray("positions") { positions.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("triangles") { triangles.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("weights") { weights.forEach { row -> add(buildJsonObject { row.forEach { (id, value) -> put(id, value) } }) } }
    }
    companion object {
        fun fromJson(o: JsonObject) = SkeletonWeightMap(o.getValue("positions").jsonArray.map { it.jsonPrimitive.float },
            o.getValue("triangles").jsonArray.map { it.jsonPrimitive.int },
            o.getValue("weights").jsonArray.map { row -> row.jsonObject.mapValues { it.value.jsonPrimitive.float } })
    }
}

enum class SkeletonWeightBrushMode { ADD, SUBTRACT, REPLACE, SMOOTH }
enum class SkeletonWeightTransferMode { INTERPOLATE, NEAREST, TOPOLOGY }

object SkeletonManualWeights {
    data class Transfer(val map: SkeletonWeightMap, val matched: Int, val unmatched: Int)

    /** The angular skinning engine supports a rigid bone or one adjacent parent/child pair. */
    private fun canonical(values: Map<String, Float>, parents: Map<String, String?>, maxInfluences: Int = 2,
        cutoff: Float = 0f, preferred: String? = null): Map<String, Float> {
        val valid = values.filter { (id, w) -> id in parents && w.isFinite() && w > cutoff }
        if (valid.isEmpty()) return emptyMap()
        val chosen = preferred?.takeIf { it in valid } ?: valid.maxBy { it.value }.key
        var best = listOf(chosen)
        var score = valid.getValue(chosen).toDouble()
        if (maxInfluences > 1) for ((child, parent) in parents) {
            if (parent == null || child !in valid || parent !in valid) continue
            if (preferred != null && preferred in valid && preferred != child && preferred != parent) continue
            val sum = valid.getValue(child).toDouble() + valid.getValue(parent).toDouble()
            if (sum > score) { best = listOf(parent, child); score = sum }
        }
        return best.associateWith { (valid.getValue(it).toDouble() / score).toFloat() }
    }

    private fun parents(spec: SkeletonSpec): Map<String, String?> = SkeletonRig.jointParents(spec).mapValues { it.value?.id }

    fun treeIds(spec: SkeletonSpec, drawableId: String): Set<String> {
        val joints = SkeletonRig.jointBones(spec)
        val owner = joints.firstOrNull { drawableId in it.drawableIds } ?: return emptySet()
        val roots = SkeletonRig.skinRoots(joints, SkeletonRig.jointParents(spec))
        return joints.filter { roots[it.id] == roots[owner.id] }.mapTo(linkedSetOf()) { it.id }
    }

    internal fun weights(canvas: FloatArray, triangles: IntArray, tree: List<SkeletonBone>, parentOf: Map<String, SkeletonBone?>,
        manual: SkeletonWeightMap?): List<VertexSkin> {
        val skinBones = SkeletonRig.skinBones(tree, parentOf)
        val automatic = SkeletonWeights.skin(canvas, skinBones, triangles)
        if (manual == null) return automatic
        val indices = tree.withIndex().associate { it.value.id to it.index }
        val parents = tree.withIndex().associate { (i, b) -> b.id to skinBones[i].parent.takeIf { it >= 0 }?.let { tree[it].id } }
        return automatic.mapIndexed { vertex, fallback ->
            val row = manual.sample(canvas[vertex * 2], canvas[vertex * 2 + 1])?.let { canonical(it, parents) }.orEmpty()
            if (row.isEmpty()) fallback else if (row.size == 1) VertexSkin(indices.getValue(row.keys.single()), indices.getValue(row.keys.single()), 0f)
            else {
                val child = row.keys.first { parents[it] in row.keys }
                VertexSkin(indices.getValue(parents.getValue(child)!!), indices.getValue(child), row.getValue(child))
            }
        }
    }

    fun capture(spec: SkeletonSpec, model: PuppetModel, drawableId: String): SkeletonWeightMap? {
        val owner = SkeletonRig.jointBones(spec).firstOrNull { drawableId in it.drawableIds } ?: return null
        val joints = SkeletonRig.jointBones(spec); val parentOf = SkeletonRig.jointParents(spec)
        val roots = SkeletonRig.skinRoots(joints, parentOf)
        val tree = joints.filter { roots[it.id] == roots[owner.id] }
        val id = DrawableId(drawableId)
        val mesh = model.drawables.firstOrNull { it.id == id }?.mesh ?: return null
        val positions = SkeletonRig.restCanvas(model)[id] ?: return null
        val skins = weights(positions, mesh.indices, tree, parentOf, spec.manualWeights[drawableId])
        val rows = skins.map { s -> if (s.rigid) mapOf(tree[s.from].id to 1f)
            else mapOf(tree[s.from].id to 1f - s.weight, tree[s.to].id to s.weight) }
        return SkeletonWeightMap(positions.toList(), mesh.indices.toList(), rows)
    }

    fun paint(spec: SkeletonSpec, map: SkeletonWeightMap, boneId: String, x: Float, y: Float, radius: Float,
        strength: Float, mode: SkeletonWeightBrushMode, replaceValue: Float = 1f): SkeletonWeightMap {
        require(radius.isFinite() && radius > 0f && strength.isFinite() && strength in 0f..1f && replaceValue in 0f..1f)
        val parents = parents(spec)
        require(boneId in parents)
        val adjacency = Array(map.weights.size) { mutableSetOf<Int>() }
        if (mode == SkeletonWeightBrushMode.SMOOTH) for (i in map.triangles.indices step 3) for (k in 0..2) {
            val a = map.triangles[i + k]; val b = map.triangles[i + (k + 1) % 3]
            adjacency[a] += b; adjacency[b] += a
        }
        val normalized = map.weights.map { canonical(it, parents) }
        val rows = normalized.mapIndexed { v, row ->
            val distance = hypot(map.positions[v * 2] - x, map.positions[v * 2 + 1] - y)
            if (distance > radius) return@mapIndexed row
            val alpha = strength * (1f - distance / radius)
            val old = row[boneId] ?: 0f
            val value = when (mode) {
                SkeletonWeightBrushMode.ADD -> old + alpha
                SkeletonWeightBrushMode.SUBTRACT -> old - alpha
                SkeletonWeightBrushMode.REPLACE -> old + (replaceValue - old) * alpha
                SkeletonWeightBrushMode.SMOOTH -> {
                    val adjacent = adjacency[v]
                    val average = if (adjacent.isEmpty()) old else adjacent.sumOf { (normalized[it][boneId] ?: 0f).toDouble() }.toFloat() / adjacent.size
                    old + (average - old) * alpha
                }
            }.coerceIn(0f, 1f)
            var others = row.filterKeys { it != boneId }
            if (mode == SkeletonWeightBrushMode.SMOOTH && adjacency[v].isNotEmpty()) {
                val average = mutableMapOf<String, Float>()
                for (neighbor in adjacency[v]) for ((id, w) in normalized[neighbor]) if (id != boneId)
                    average[id] = (average[id] ?: 0f) + w / adjacency[v].size
                if (average.isNotEmpty()) others = average
            }
            if (others.values.sum() <= 0f && value < 1f) {
                val adjacent = parents.keys.filter { it != boneId && (parents[boneId] == it || parents[it] == boneId) }
                    .mapNotNull(spec::bone).minByOrNull { b ->
                        val px = map.positions[v * 2]; val py = map.positions[v * 2 + 1]
                        val bx = b.tailX - b.headX; val by = b.tailY - b.headY
                        val u = (((px - b.headX) * bx + (py - b.headY) * by) / (bx * bx + by * by).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                        hypot(px - b.headX - bx * u, py - b.headY - by * u)
                    }
                if (adjacent != null) others = mapOf(adjacent.id to 1f)
            }
            val sum = others.values.sum()
            val next = others.mapValues { if (sum > 0f) it.value * (1f - value) / sum else 0f } + (boneId to value)
            canonical(next, parents, preferred = boneId).ifEmpty { row }
        }
        return map.copy(weights = rows)
    }

    fun cleanup(spec: SkeletonSpec, map: SkeletonWeightMap, fallback: SkeletonWeightMap,
        maxInfluences: Int = 2, cutoff: Float = 0.001f): SkeletonWeightMap {
        require(maxInfluences in 1..2 && cutoff.isFinite() && cutoff in 0f..1f)
        val allParents = parents(spec)
        fun root(id: String) = generateSequence(id) { allParents[it] }.last()
        val roots = fallback.weights.flatMap { it.keys }.filter { it in allParents }.map(::root).toSet()
        val parents = allParents.filterKeys { root(it) in roots }
        return map.copy(weights = map.weights.mapIndexed { i, row ->
            canonical(row, parents, maxInfluences, cutoff).ifEmpty {
                canonical(fallback.sample(map.positions[i * 2], map.positions[i * 2 + 1]).orEmpty(), parents, maxInfluences)
            }
        })
    }

    fun transfer(source: SkeletonWeightMap, target: SkeletonWeightMap, boneMapping: Map<String, String>,
        mode: SkeletonWeightTransferMode, tolerancePx: Float = 10f, mirrorAxis: Float? = null): Transfer {
        require(tolerancePx.isFinite() && tolerancePx >= 0f && (mirrorAxis == null || mirrorAxis.isFinite()))
        val projected = if (mirrorAxis == null) source else source.copy(positions = source.positions.mapIndexed { i, v -> if (i % 2 == 0) 2 * mirrorAxis - v else v })
        if (mode == SkeletonWeightTransferMode.TOPOLOGY) require(source.weights.size == target.weights.size && source.triangles == target.triangles) { "Meshes do not share topology" }
        var matched = 0
        val rows = target.weights.indices.map { i ->
            val x = target.positions[i * 2]; val y = target.positions[i * 2 + 1]
            val sampled = when (mode) {
                SkeletonWeightTransferMode.TOPOLOGY -> projected.weights[i]
                SkeletonWeightTransferMode.INTERPOLATE -> projected.sample(x, y)
                SkeletonWeightTransferMode.NEAREST -> projected.weights.indices.minByOrNull { v -> hypot(projected.positions[v * 2] - x, projected.positions[v * 2 + 1] - y) }
                    ?.takeIf { v -> hypot(projected.positions[v * 2] - x, projected.positions[v * 2 + 1] - y) <= tolerancePx }?.let { projected.weights[it] }
            }
            val row = mutableMapOf<String, Float>()
            for ((id, value) in sampled.orEmpty()) if (value > 0f) boneMapping[id]?.let { to -> row[to] = (row[to] ?: 0f) + value }
            if (row.isNotEmpty()) matched++
            row.toMap()
        }
        return Transfer(target.copy(weights = rows), matched, rows.size - matched)
    }
}
