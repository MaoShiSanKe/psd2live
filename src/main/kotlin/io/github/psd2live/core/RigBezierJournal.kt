package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.security.MessageDigest

/** Editable controls live in the journal; the runtime and CMO3 retain the sampled Warp lattice. */
internal object RigBezierJournal {
    const val OP = "warp_bezier"
    data class Controls(val state: BezierDeformerState, val residual: FloatArray, val persisted: Boolean = false)

    fun divisions(overlay: RigEditOverlay, id: String): Pair<Int, Int> = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP && it["id"]?.jsonPrimitive?.contentOrNull == id
    }?.getValue("controls")?.jsonObject?.let { it.getValue("rows").jsonPrimitive.int to it.getValue("columns").jsonPrimitive.int }
        ?: BezierWarp.importedDivisions(overlay, id) ?: (2 to 2)

    fun read(model: PuppetModel, overlay: RigEditOverlay, id: String, key: Map<String, Float>, pose: Map<String, Float> = key): Controls {
        val geometry = RigGeometryTools.geometry(model, "warp", id, pose)
        val basis = fingerprint(geometry.points)
        val (rows, columns) = divisions(overlay, id)
        val saved = overlay.authoringJournal.lastOrNull { record ->
            record["op"]?.jsonPrimitive?.contentOrNull == OP && record["id"]?.jsonPrimitive?.contentOrNull == id &&
                coordinate(record.getValue("key")) == key && record["basis"]?.jsonPrimitive?.contentOrNull == basis &&
                record.getValue("controls").jsonObject.let { it.getValue("rows").jsonPrimitive.int == rows && it.getValue("columns").jsonPrimitive.int == columns }
        }
        if (saved != null) return decode(saved.getValue("controls").jsonObject, geometry.points.size).copy(persisted = true)
        // Non-Bezier editing, changing pose, or changing conversion topology invalidates the
        // saved sampled basis. Reconstruct controls and retain the unsampled detail as residual.
        val state = BezierDeformerState(rows, columns).apply { initFromLattice(geometry.points, geometry.rows!!, geometry.columns!!) }
        val sampled = state.evaluateLattice(geometry.rows!!, geometry.columns!!)
        return Controls(state, FloatArray(sampled.size) { geometry.points[it] - sampled[it] })
    }

    fun prepare(model: PuppetModel, overlay: RigEditOverlay, action: String, request: JsonObject): JsonObject {
        val target = request.getValue("target").jsonPrimitive.content
        require(target.startsWith("warp:") && target.substringAfter(':').isNotBlank()) { "Use a warp:id handle" }
        val id = target.substringAfter(':'); val key = request["coordinate"]?.let(::coordinate).orEmpty()
        val pose = request["pose"]?.let(::coordinate).orEmpty() + key
        val geometry = RigGeometryTools.geometry(model, "warp", id, pose)
        val existing = read(model, overlay, id, key, pose)
        val controls = when (action) {
            "divisions", "reset" -> {
                val rows = request["rows"]?.jsonPrimitive?.int ?: existing.state.bezierRows
                val columns = request["columns"]?.jsonPrimitive?.int ?: existing.state.bezierCols
                require(rows in 1..16 && columns in 1..16) { "Bezier divisions must be in 1..16" }
                val state = BezierDeformerState(rows, columns).apply { initFromLattice(geometry.points, geometry.rows!!, geometry.columns!!) }
                val sampled = state.evaluateLattice(geometry.rows!!, geometry.columns!!)
                Controls(state, if (action == "reset") FloatArray(sampled.size) else FloatArray(sampled.size) { geometry.points[it] - sampled[it] })
            }
            "anchor", "handle" -> {
                val row = request.getValue("row").jsonPrimitive.int; val column = request.getValue("column").jsonPrimitive.int
                val x = request.getValue("x").jsonPrimitive.float; val y = request.getValue("y").jsonPrimitive.float
                require(x.isFinite() && y.isFinite()) { "Bezier control coordinates must be finite" }
                val anchor = requireNotNull(existing.state.anchors[row to column]) { "Bezier anchor is outside the control grid" }
                if (action == "anchor") existing.state.moveAnchor(row, column, x - anchor.x, y - anchor.y) else {
                    val direction = BezierHandleDir.valueOf(request.getValue("direction").jsonPrimitive.content.uppercase())
                    require(Triple(row, column, direction) in existing.state.handles) { "Bezier handle does not exist on this boundary" }
                    existing.state.moveHandle(row, column, direction, x, y, request["smooth"]?.jsonPrimitive?.boolean ?: true)
                }
                existing
            }
            else -> error("Unknown Bezier operation: $action")
        }
        return materialize(model, id, key, pose, controls, action != "divisions", request["preserve_children"]?.jsonPrimitive?.boolean ?: false)
    }

    fun materialize(model: PuppetModel, id: String, key: Map<String, Float>, pose: Map<String, Float>, controls: Controls,
                    changeGeometry: Boolean = true, preserveChildren: Boolean = false): JsonObject {
        RigWarpTopology.checkpoint()
        val geometry = RigGeometryTools.geometry(model, "warp", id, pose)
        val blend = key.keys.any { name -> model.parameters.any { it.id.raw == name && it.kind == ParameterKind.BLEND_SHAPE } }
        require(key.isEmpty() || blend || geometry.axes.all { it.parameterId.raw in key }) { "Include every bound geometry axis in coordinate" }
        require(controls.state.bezierRows in 1..16 && controls.state.bezierCols in 1..16 && controls.residual.size == geometry.points.size)
        val sampled = controls.state.evaluateLattice(geometry.rows!!, geometry.columns!!)
        val points = FloatArray(sampled.size) { sampled[it] + controls.residual[it] }.also { require(it.all(Float::isFinite)) }
        val edit = if (changeGeometry) buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", id)
            put("key", keyJson(key)); put("pose", keyJson(pose)); put("points", RigWarpTopology.floats(points))
            put("preserve_children", preserveChildren)
        } else null
        val after = if (edit == null) model else CanvasEdits.apply(model, edit)
        val basis = fingerprint(RigGeometryTools.geometry(after, "warp", id, pose).points)
        return buildJsonObject {
            put("op", OP); put("id", id); put("key", keyJson(key)); put("pose", keyJson(pose))
            put("expected", fingerprint(geometry.points)); put("basis", basis); put("geometry", edit ?: JsonNull)
            put("controls", encode(controls))
        }
    }

    fun replay(model: PuppetModel, record: JsonObject): PuppetModel {
        require(record.keys == setOf("op", "id", "key", "pose", "expected", "basis", "geometry", "controls")) { "Invalid Bezier record" }
        val id = record.getValue("id").jsonPrimitive.content; val pose = coordinate(record.getValue("pose"))
        val before = RigGeometryTools.geometry(model, "warp", id, pose)
        require(fingerprint(before.points) == record.getValue("expected").jsonPrimitive.content) { "Bezier geometry baseline changed" }
        decode(record.getValue("controls").jsonObject, before.points.size)
        val next = record.getValue("geometry").takeIf { it != JsonNull }?.jsonObject?.let { edit ->
            require(edit["op"]?.jsonPrimitive?.content == "canvas_geometry" && edit["kind"]?.jsonPrimitive?.content == "warp" && edit["id"]?.jsonPrimitive?.content == id &&
                edit["key"] == record["key"] && edit["pose"] == record["pose"]) { "Bezier geometry target changed" }
            CanvasEdits.apply(model, edit)
        } ?: model
        require(fingerprint(RigGeometryTools.geometry(next, "warp", id, pose).points) == record.getValue("basis").jsonPrimitive.content) { "Bezier sampled basis changed" }
        return next
    }

    fun encode(controls: Controls): JsonObject = buildJsonObject {
        put("rows", controls.state.bezierRows); put("columns", controls.state.bezierCols)
        put("residual", RigWarpTopology.floats(controls.residual))
        put("anchors", JsonArray(controls.state.anchors.values.sortedWith(compareBy({ it.row }, { it.col })).map { a ->
            buildJsonArray { add(a.row); add(a.col); add(a.x); add(a.y) }
        }))
        put("handles", JsonArray(controls.state.handles.values.sortedWith(compareBy({ it.row }, { it.col }, { it.dir.ordinal })).map { h ->
            buildJsonArray { add(h.row); add(h.col); add(h.dir.name.lowercase()); add(h.x); add(h.y) }
        }))
    }

    private fun decode(value: JsonObject, size: Int): Controls {
        require(value.keys == setOf("rows", "columns", "residual", "anchors", "handles")) { "Invalid Bezier controls" }
        val rows = value.getValue("rows").jsonPrimitive.int; val columns = value.getValue("columns").jsonPrimitive.int
        require(rows in 1..16 && columns in 1..16)
        val state = BezierDeformerState(rows, columns)
        value.getValue("anchors").jsonArray.forEach { element ->
            RigWarpTopology.checkpoint(); val a = element.jsonArray; require(a.size == 4)
            val row = a[0].jsonPrimitive.int; val column = a[1].jsonPrimitive.int; val x = a[2].jsonPrimitive.float; val y = a[3].jsonPrimitive.float
            require(row in 0..rows && column in 0..columns && x.isFinite() && y.isFinite() && (row to column) !in state.anchors)
            state.anchors[row to column] = BezierAnchor(row, column, x, y)
        }
        require(state.anchors.size == (rows + 1) * (columns + 1))
        value.getValue("handles").jsonArray.forEach { element ->
            RigWarpTopology.checkpoint(); val h = element.jsonArray; require(h.size == 5)
            val row = h[0].jsonPrimitive.int; val column = h[1].jsonPrimitive.int
            val direction = BezierHandleDir.valueOf(h[2].jsonPrimitive.content.uppercase())
            val x = h[3].jsonPrimitive.float; val y = h[4].jsonPrimitive.float
            val key = Triple(row, column, direction)
            require(row in 0..rows && column in 0..columns && x.isFinite() && y.isFinite() && key !in state.handles)
            require(when (direction) { BezierHandleDir.LEFT -> column > 0; BezierHandleDir.RIGHT -> column < columns; BezierHandleDir.TOP -> row > 0; BezierHandleDir.BOTTOM -> row < rows })
            state.handles[key] = BezierHandle(row, column, direction, x, y)
        }
        require(state.handles.size == rows * (columns + 1) * 2 + columns * (rows + 1) * 2)
        val residual = value.getValue("residual").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        require(residual.size == size && residual.all(Float::isFinite))
        return Controls(state, residual)
    }

    private fun coordinate(value: JsonElement) = value.jsonObject.mapValues { it.value.jsonPrimitive.float }
    private fun keyJson(key: Map<String, Float>) = buildJsonObject { key.toSortedMap().forEach { (id, value) -> put(id, value) } }
    private fun fingerprint(points: FloatArray): String = MessageDigest.getInstance("SHA-256")
        .digest(RigWarpTopology.floats(points).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
