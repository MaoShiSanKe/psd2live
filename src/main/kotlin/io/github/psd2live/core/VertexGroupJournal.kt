package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.hypot

/**
 * Vertex groups in the authoring journal. A put carries the whole materialized weight array, so replay
 * never re-runs a brush or a rule; topology edits later in the journal remap it (withMeshTopologyEdit).
 *
 * MCP callers describe weights by rule instead (`vertex_group_rule`), which [compileRule] turns into a
 * put against the model the command lands on - no per-vertex arrays cross the MCP boundary.
 */
internal object VertexGroupJournal {
    const val PUT = "vertex_group_put"
    const val DELETE = "vertex_group_delete"
    const val RULE = "vertex_group_rule"

    fun encode(group: VertexGroup): JsonObject = buildJsonObject {
        put("op", PUT); put("target", "mesh:${group.drawableId.raw}"); put("name", group.name); put("kind", group.kind.jsonName)
        put("weights", JsonArray(group.weights.map { JsonPrimitive(round(it)) }))
    }

    fun delete(drawableId: String, name: String): JsonObject = buildJsonObject {
        put("op", DELETE); put("target", "mesh:$drawableId"); put("name", name)
    }

    fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
        val drawableId = meshTarget(command)
        val name = command.getValue("name").jsonPrimitive.content
        if (command.getValue("op").jsonPrimitive.content == DELETE) {
            // A retired group was never loaded, so its delete has nothing to remove.
            return model.copy(vertexGroups = model.vertexGroups.filterNot { it.drawableId == drawableId && it.name == name })
        }
        val kindName = command.getValue("kind").jsonPrimitive.content
        if (kindName.lowercase() in VertexGroupKind.RETIRED) return model
        val mesh = requireNotNull(model.drawables.singleOrNull { it.id == drawableId }?.mesh) { "Mesh not found: ${drawableId.raw}" }
        val weights = command.getValue("weights").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        require(weights.size == mesh.vertexCount) { "Vertex group has ${weights.size} weights for ${mesh.vertexCount} vertices" }
        val kind = VertexGroupKind.parse(kindName)
        val group = VertexGroup(name, drawableId, kind, weights)
        val others = model.vertexGroups.filterNot { it.drawableId == drawableId && it.name == name }
        return model.copy(vertexGroups = others + group)
    }

    fun isNoOp(model: PuppetModel, command: JsonObject): Boolean {
        if (command.getValue("op").jsonPrimitive.content != PUT) return false
        val drawableId = meshTarget(command)
        val existing = model.vertexGroups.firstOrNull { it.drawableId == drawableId && it.name == command.getValue("name").jsonPrimitive.content }
            ?: return false
        val weights = command.getValue("weights").jsonArray
        return existing.kind.jsonName == command.getValue("kind").jsonPrimitive.content &&
            weights.size == existing.weights.size && weights.indices.all { weights[it].jsonPrimitive.float == existing.weights[it] }
    }

    /**
     * A `vertex_group_rule` command as the put it stands for:
     * - `fill`: every vertex [value];
     * - `outline`: outline vertices [value], others unchanged (0 for a new group);
     * - `gradient`: projected along `from`→`to` (canvas px), `start` at `from` to `end` at `to`, clamped;
     * - `glue`: vertices in any glue pair with another mesh take the pair's weight on this side;
     * - `region`: vertices inside the `rect` [x0, y0, x1, y1] take [value].
     * `mode` combines with the existing group: `replace` (default), `max`, `min`, `add`, `subtract`.
     */
    fun compileRule(model: PuppetModel, command: JsonObject): JsonObject {
        val drawableId = meshTarget(command)
        val name = command.getValue("name").jsonPrimitive.content
        val mesh = requireNotNull(model.drawables.singleOrNull { it.id == drawableId }?.mesh) { "Mesh not found: ${drawableId.raw}" }
        val existing = model.vertexGroups.firstOrNull { it.drawableId == drawableId && it.name == name }
        val kind = command["kind"]?.jsonPrimitive?.content?.let(VertexGroupKind::parse) ?: existing?.kind
            ?: throw IllegalArgumentException("kind is required for a new vertex group")
        val value = command["value"]?.jsonPrimitive?.float ?: 1f
        require(value.isFinite() && value in 0f..1f) { "value must be within 0..1" }
        val count = mesh.vertexCount
        val positions = mesh.positions
        val base = existing?.weights ?: FloatArray(count)
        val rule = command.getValue("rule").jsonPrimitive.content
        val computed: FloatArray = when (rule) {
            "fill" -> FloatArray(count) { value }
            "outline" -> {
                val outline = outlineVertices(mesh.indices, count)
                FloatArray(count) { if (it in outline) value else base[it] }
            }
            "gradient" -> {
                val from = command.point("from")
                val to = command.point("to")
                val start = command["start"]?.jsonPrimitive?.float ?: 1f
                val end = command["end"]?.jsonPrimitive?.float ?: 0f
                val dx = to.first - from.first
                val dy = to.second - from.second
                val length2 = dx * dx + dy * dy
                require(length2 > 1e-6f) { "Gradient needs two distinct points" }
                FloatArray(count) {
                    val t = (((positions[it * 2] - from.first) * dx + (positions[it * 2 + 1] - from.second) * dy) / length2).coerceIn(0f, 1f)
                    (start + (end - start) * t).coerceIn(0f, 1f)
                }
            }
            "glue" -> {
                val weights = glueSideWeights(model, drawableId, count)
                FloatArray(count) { if (weights[it] > 0f) weights[it] * value else base[it] }
            }
            "region" -> {
                val rect = command.getValue("rect").jsonArray.map { it.jsonPrimitive.float }
                require(rect.size == 4) { "rect is [x0, y0, x1, y1]" }
                val (x0, x1) = minOf(rect[0], rect[2]) to maxOf(rect[0], rect[2])
                val (y0, y1) = minOf(rect[1], rect[3]) to maxOf(rect[1], rect[3])
                FloatArray(count) {
                    val x = positions[it * 2]
                    val y = positions[it * 2 + 1]
                    if (x in x0..x1 && y in y0..y1) value else base[it]
                }
            }
            else -> throw IllegalArgumentException("Unknown vertex group rule: $rule (fill, outline, gradient, glue, region)")
        }
        val mode = command["mode"]?.jsonPrimitive?.content ?: "replace"
        val combined = FloatArray(count) {
            when (mode) {
                "replace" -> computed[it]
                "max" -> maxOf(base[it], computed[it])
                "min" -> minOf(base[it], computed[it])
                "add" -> (base[it] + computed[it]).coerceIn(0f, 1f)
                "subtract" -> (base[it] - computed[it]).coerceIn(0f, 1f)
                else -> throw IllegalArgumentException("Unknown mode: $mode (replace, max, min, add, subtract)")
            }
        }
        return encode(VertexGroup(name, drawableId, kind, combined))
    }

    /** Per-vertex weight of [drawableId]'s side of every glue it shares with another mesh; 0 where unglued. */
    fun glueSideWeights(model: PuppetModel, drawableId: DrawableId, vertexCount: Int): FloatArray = glueVertexWeights(model, drawableId, vertexCount)

    /**
     * [group] carried onto [replacement], a rebuilt mesh of the same artwork: each new vertex takes the
     * weights of the old triangle it lies in (barycentric), or of the nearest old vertex outside the old mesh.
     */
    fun resample(group: VertexGroup, previous: DrawableMesh, replacement: DrawableMesh): VertexGroup {
        val old = previous.positions
        val weights = FloatArray(replacement.vertexCount) { vertex ->
            val x = replacement.positions[vertex * 2]
            val y = replacement.positions[vertex * 2 + 1]
            sampleTriangle(previous, group.weights, x, y) ?: run {
                var best = 0
                var bestDistance = Float.MAX_VALUE
                for (i in 0 until previous.vertexCount) {
                    val d = hypot(old[i * 2] - x, old[i * 2 + 1] - y)
                    if (d < bestDistance) { bestDistance = d; best = i }
                }
                group.weights.getOrElse(best) { 0f }
            }
        }
        return group.copy(weights = FloatArray(weights.size) { weights[it].coerceIn(0f, 1f) })
    }

    /**
     * [overlay] with the journaled vertex groups of [drawableId] replaced by [surviving], each already on the
     * rebuilt mesh. The old puts carried the old vertex count and would no longer apply.
     */
    fun replaceMeshGroups(overlay: RigEditOverlay, drawableId: String, surviving: List<VertexGroup>): RigEditOverlay {
        val meshTarget = "mesh:$drawableId"
        val retained = overlay.authoringJournal.filterNot { command ->
            val op = command["op"]?.jsonPrimitive?.contentOrNull
            (op == PUT || op == DELETE) && command["target"]?.jsonPrimitive?.contentOrNull == meshTarget
        }
        return overlay.copy(authoringJournal = retained + surviving.map(::encode))
    }

    /** The groups of each rebuilt mesh in [previous], resampled onto the same mesh in [replacement]. */
    fun rebuiltGroups(previous: PuppetModel, replacement: PuppetModel, drawableId: String): List<VertexGroup> {
        val oldMesh = previous.drawables.firstOrNull { it.id.raw == drawableId }?.mesh ?: return emptyList()
        val newMesh = replacement.drawables.firstOrNull { it.id.raw == drawableId }?.mesh ?: return emptyList()
        return previous.vertexGroups.filter { it.drawableId.raw == drawableId }.map { resample(it, oldMesh, newMesh) }
    }

    private fun sampleTriangle(mesh: DrawableMesh, weights: FloatArray, x: Float, y: Float): Float? {
        val p = mesh.positions
        for (t in 0 until mesh.indices.size / 3) {
            val a = mesh.indices[t * 3]
            val b = mesh.indices[t * 3 + 1]
            val c = mesh.indices[t * 3 + 2]
            val denominator = (p[b * 2 + 1] - p[c * 2 + 1]) * (p[a * 2] - p[c * 2]) + (p[c * 2] - p[b * 2]) * (p[a * 2 + 1] - p[c * 2 + 1])
            if (kotlin.math.abs(denominator) < 1e-12f) continue
            val wa = ((p[b * 2 + 1] - p[c * 2 + 1]) * (x - p[c * 2]) + (p[c * 2] - p[b * 2]) * (y - p[c * 2 + 1])) / denominator
            val wb = ((p[c * 2 + 1] - p[a * 2 + 1]) * (x - p[c * 2]) + (p[a * 2] - p[c * 2]) * (y - p[c * 2 + 1])) / denominator
            val wc = 1f - wa - wb
            if (wa >= -1e-4f && wb >= -1e-4f && wc >= -1e-4f) {
                return weights.getOrElse(a) { 0f } * wa + weights.getOrElse(b) { 0f } * wb + weights.getOrElse(c) { 0f } * wc
            }
        }
        return null
    }

    private fun meshTarget(command: JsonObject): DrawableId {
        val target = RigAuthoringJournal.target(command.getValue("target").jsonPrimitive.content)
        require(target.kind == RigTargetKind.ART_MESH) { "Vertex groups belong to an ArtMesh" }
        return DrawableId(target.id)
    }

    private fun JsonObject.point(key: String): Pair<Float, Float> {
        val values = getValue(key).jsonArray.map { it.jsonPrimitive.float }
        require(values.size == 2) { "$key is [x, y]" }
        return values[0] to values[1]
    }

    /** Four decimals: enough for any brush, and keeps the journal readable. */
    private fun round(value: Float): Float = kotlin.math.round(value * 10000f) / 10000f
}
