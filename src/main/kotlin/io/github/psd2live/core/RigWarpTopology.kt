package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.warpApply
import org.umamo.runtime.model.*
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** A lattice change samples the actual runtime surface, including every additive reference form. */
internal object RigWarpTopology {
    const val OP = "warp_topology"

    internal fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Warp edit cancelled")
    }

    fun fingerprint(warp: Deformer.Warp): String {
        val data = buildJsonObject {
            put("rows", warp.rows); put("columns", warp.columns); put("quad", warp.isQuadTransform)
            put("geometry", warp.geometryGrid?.let { RasterMeshCreation.grid(it) { f -> floats(f.controlPoints) } } ?: JsonNull)
            put("blends", JsonArray(warp.blendShapes.map { b -> buildJsonObject {
                put("parameter", b.parameterId.raw); put("keys", floats(b.keys)); put("neutral", b.neutralIndex)
                put("forms", JsonArray(b.forms.map { it?.let { f -> floats(f.controlPoints) } ?: JsonNull }))
            } }))
        }.toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun prepare(model: PuppetModel, id: String, rows: Int, columns: Int, check: () -> Unit = ::checkpoint): JsonObject {
        require(rows in 1..32 && columns in 1..32) { "Warp divisions must be in 1..32" }
        val warp = model.deformers.singleOrNull { it.id.raw == id } as? Deformer.Warp ?: error("Warp not found: $id")
        val size = (warp.rows + 1) * (warp.columns + 1) * 2
        fun resample(points: FloatArray): FloatArray {
            require(points.size == size && points.all(Float::isFinite)) { "Warp has a stale control-point inventory" }
            val result = FloatArray((rows + 1) * (columns + 1) * 2)
            for (r in 0..rows) {
                check()
                for (c in 0..columns) warpApply(points, warp.columns, warp.rows, warp.isQuadTransform,
                    c.toFloat() / columns, r.toFloat() / rows, result, (r * (columns + 1) + c) * 2)
            }
            return result
        }
        val forms = warp.geometryGrid?.cells.orEmpty().map { resample(it.form.controlPoints) }
        val blends = warp.blendShapes.map { b -> b.forms.map { it?.let { form -> resample(form.controlPoints) } } }
        var error = 0f
        fun measure(old: FloatArray, next: FloatArray) {
            val a = FloatArray(2); val b = FloatArray(2)
            for (r in 0..64) {
                check()
                for (c in 0..64) {
                    warpApply(old, warp.columns, warp.rows, warp.isQuadTransform, c / 64f, r / 64f, a, 0)
                    warpApply(next, columns, rows, warp.isQuadTransform, c / 64f, r / 64f, b, 0)
                    error = maxOf(error, abs(a[0] - b[0]), abs(a[1] - b[1]))
                }
            }
        }
        warp.geometryGrid?.cells.orEmpty().forEachIndexed { index, cell -> measure(cell.form.controlPoints, forms[index]) }
        warp.blendShapes.forEachIndexed { index, binding -> binding.forms.forEachIndexed { f, form ->
            if (form != null) measure(form.controlPoints, requireNotNull(blends[index][f]))
        } }
        return buildJsonObject {
            put("op", OP); put("id", id); put("expected", fingerprint(warp)); put("rows", rows); put("columns", columns)
            put("forms", JsonArray(forms.map(::floats))); put("blends", JsonArray(blends.map { JsonArray(it.map { p -> p?.let(::floats) ?: JsonNull }) }))
            put("max_surface_error", error); put("error_space", "parent_local"); put("probe_divisions", 64)
        }
    }

    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        require(command.keys == setOf("op", "id", "expected", "rows", "columns", "forms", "blends", "max_surface_error", "error_space", "probe_divisions")) { "Invalid Warp topology record" }
        require(command.getValue("max_surface_error").jsonPrimitive.float.let { it.isFinite() && it >= 0f } &&
            command.getValue("error_space").jsonPrimitive.content == "parent_local" && command.getValue("probe_divisions").jsonPrimitive.int == 64)
        val id = command.getValue("id").jsonPrimitive.content
        val warp = model.deformers.singleOrNull { it.id.raw == id } as? Deformer.Warp ?: error("Warp not found: $id")
        require(fingerprint(warp) == command.getValue("expected").jsonPrimitive.content) { "Warp topology baseline changed" }
        val rows = command.getValue("rows").jsonPrimitive.int; val columns = command.getValue("columns").jsonPrimitive.int
        require(rows in 1..32 && columns in 1..32)
        val size = (rows + 1) * (columns + 1) * 2
        fun points(value: JsonElement) = value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
            checkpoint(); require(it.size == size && it.all(Float::isFinite)) { "Invalid Warp topology form" }
        }
        val forms = command.getValue("forms").jsonArray
        val blends = command.getValue("blends").jsonArray
        require(forms.size == warp.geometryGrid?.cells.orEmpty().size && blends.size == warp.blendShapes.size) { "Warp form inventory changed" }
        val grid = warp.geometryGrid?.let { old -> KeyformGrid(old.axes, old.cells.mapIndexed { index, cell ->
            KeyformCell(cell.coordinate, WarpLatticeForm(points(forms[index])))
        }) }
        val bindings = warp.blendShapes.mapIndexed { index, binding ->
            val values = blends[index].jsonArray; require(values.size == binding.forms.size)
            binding.copy(forms = binding.forms.mapIndexed { f, form ->
                require((form == null) == (values[f] == JsonNull)) { "Warp blend key inventory changed" }
                form?.let { WarpForm(points(values[f]), it.opacity, it.multiplyColor, it.screenColor) }
            })
        }
        if (rows == warp.rows && columns == warp.columns && grid == warp.geometryGrid && bindings == warp.blendShapes) return model
        val replacement = warp.copy(rows = rows, columns = columns, geometryGrid = grid, blendShapes = bindings)
        return model.copy(deformers = model.deformers.map { if (it.id == warp.id) replacement else it })
    }

    internal fun floats(points: FloatArray) = JsonArray(points.map(::JsonPrimitive))
}
