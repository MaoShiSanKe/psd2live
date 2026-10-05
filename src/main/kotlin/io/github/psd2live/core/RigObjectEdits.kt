package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import org.umamo.runtime.eval.meshGridDefaultDeltas

/** An identity child warp preserves every existing mesh keyform and the inherited deformation. */
data class RigWarpEdit(val id: String, val name: String, val parentId: String,
    val meshIds: List<String>, val rows: Int = 4, val columns: Int = 4, val fitLocal: Boolean = false) {
    init {
        require(listOf(id, name, parentId).all { it.isNotBlank() && it.none(Char::isISOControl) })
        require(id != parentId && rows in 1..32 && columns in 1..32)
        require(meshIds.isNotEmpty() && meshIds.distinct().size == meshIds.size)
    }

    fun applyTo(model: PuppetModel): PuppetModel {
        require(model.deformers.none { it.id.raw == id } && model.drawables.none { it.id.raw == id }) { "Object ID already exists: $id" }
        val parent = model.deformers.singleOrNull { it.id.raw == parentId } as? Deformer.Warp
            ?: error("Parent must be an existing Warp; inspect object_get for the mesh parent")
        for (meshId in meshIds) {
            val mesh = model.drawables.singleOrNull { it.id.raw == meshId }
                ?: error("Mesh not found: $meshId")
            require(mesh.parentDeformerId == parent.id) { "All meshes must share parent $parentId; moving across coordinate spaces is unsupported" }
        }
        // The renderer bakes parent deformation at child lattice nodes. Align knots with the
        // parent (and its triangle diagonals), otherwise even an identity child resamples motion.
        require(parent.rows > 0 && parent.columns > 0)
        val refinement = maxOf(1, (rows + parent.rows - 1) / parent.rows, (columns + parent.columns - 1) / parent.columns)
        val effectiveRows = parent.rows * refinement
        val effectiveColumns = parent.columns * refinement
        require(effectiveRows <= 64 && effectiveColumns <= 64) { "Parent-aligned Warp lattice exceeds 64 divisions" }
        val points = FloatArray((effectiveRows + 1) * (effectiveColumns + 1) * 2)
        var i = 0
        for (r in 0..effectiveRows) for (c in 0..effectiveColumns) {
            points[i++] = c.toFloat() / effectiveColumns
            points[i++] = r.toFloat() / effectiveRows
        }
        if (fitLocal) {
            // Crop on parent lattice knots. Unlike an arbitrary tight rectangle, this retains the
            // inherited piecewise-linear interpolation and its triangle boundaries.
            val targets = model.drawables.filter { it.id.raw in meshIds }
            val positions = targets.flatMap { drawable ->
                val base = requireNotNull(drawable.mesh).positions
                val gridPositions = listOf(base) + drawable.geometryGrid?.cells.orEmpty().map { cell ->
                    require(cell.form.positionDeltas.size == base.size)
                    FloatArray(base.size) { base[it] + cell.form.positionDeltas[it] }
                }
                val reference = meshGridDefaultDeltas(drawable) { parameter ->
                    model.parameters.singleOrNull { it.id == parameter }?.default ?: 0f
                }
                val minimum = FloatArray(base.size)
                val maximum = FloatArray(base.size)
                // Each binding is a convex combination of its keys and neutral. Independent
                // bindings add, so their component envelopes must add before cropping the lattice.
                for (binding in drawable.blendShapes) {
                    val forms = binding.forms.filterIndexed { index, _ -> index != binding.neutralIndex }.filterNotNull()
                    require(forms.all { it.positionDeltas.size == base.size }) { "Blend shape geometry dimensions do not match the mesh" }
                    for (component in base.indices) {
                        val deltas = forms.map { it.positionDeltas[component] - (reference?.get(component) ?: 0f) }
                        minimum[component] += minOf(0f, deltas.minOrNull() ?: 0f)
                        maximum[component] += maxOf(0f, deltas.maxOrNull() ?: 0f)
                    }
                }
                gridPositions.flatMap { geometry -> listOf(
                    FloatArray(base.size) { geometry[it] + minimum[it] },
                    FloatArray(base.size) { geometry[it] + maximum[it] }) }
            }
            val xs = positions.flatMap { p -> p.indices.step(2).map { p[it] } }
            val ys = positions.flatMap { p -> (1 until p.size step 2).map { p[it] } }
            require(xs.isNotEmpty() && ys.isNotEmpty()) { "Cannot fit empty geometry" }
            require(xs.all { it.isFinite() && it in 0f..1f } && ys.all { it.isFinite() && it in 0f..1f }) {
                "Local Warp fit requires the grid and combined blend-shape envelope inside the parent domain; use fit_local=false or extend the parent first"
            }
            val left = maxOf(0, kotlin.math.floor(xs.min() * effectiveColumns).toInt() - 1)
            val right = minOf(effectiveColumns, kotlin.math.ceil(xs.max() * effectiveColumns).toInt() + 1)
            val top = maxOf(0, kotlin.math.floor(ys.min() * effectiveRows).toInt() - 1)
            val bottom = minOf(effectiveRows, kotlin.math.ceil(ys.max() * effectiveRows).toInt() + 1)
            val localRows = bottom - top; val localColumns = right - left
            require(localRows > 0 && localColumns > 0)
            val x0 = left.toFloat() / effectiveColumns; val y0 = top.toFloat() / effectiveRows
            val width = localColumns.toFloat() / effectiveColumns; val height = localRows.toFloat() / effectiveRows
            val localPoints = FloatArray((localRows + 1) * (localColumns + 1) * 2)
            var at = 0
            for (r in top..bottom) for (c in left..right) {
                localPoints[at++] = c.toFloat() / effectiveColumns
                localPoints[at++] = r.toFloat() / effectiveRows
            }
            val child = Deformer.Warp(DeformerId(id), name, parent.id, parent.partId, localRows, localColumns, parent.isQuadTransform,
                KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(localPoints)))))
            return model.copy(deformers = model.deformers + child, drawables = model.drawables.map { drawable ->
                if (drawable.id.raw !in meshIds) drawable else {
                    val mesh = requireNotNull(drawable.mesh)
                    drawable.copy(parentDeformerId = child.id,
                        mesh = DrawableMesh(positions = FloatArray(mesh.positions.size) { j ->
                            if (j % 2 == 0) (mesh.positions[j] - x0) / width else (mesh.positions[j] - y0) / height
                        }, uvs = mesh.uvs, indices = mesh.indices), geometryGrid = drawable.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map { cell ->
                            KeyformCell(cell.coordinate, MeshDeltaForm(FloatArray(cell.form.positionDeltas.size) { j ->
                                cell.form.positionDeltas[j] / if (j % 2 == 0) width else height
                            }))
                        }) }, blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                            form?.let { MeshForm(positionDeltas = FloatArray(form.positionDeltas.size) { j ->
                                form.positionDeltas[j] / if (j % 2 == 0) width else height
                            }, drawOrder = form.drawOrder, opacity = form.opacity,
                                multiplyColor = form.multiplyColor, screenColor = form.screenColor) }
                        }) })
                }
            }).withDerivedRenderRoot()
        }
        val warp = Deformer.Warp(DeformerId(id), name, parent.id, parent.partId, effectiveRows, effectiveColumns, parent.isQuadTransform,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
        return model.copy(deformers = model.deformers + warp,
            drawables = model.drawables.map { if (it.id.raw in meshIds) it.copy(parentDeformerId = warp.id) else it })
            .withDerivedRenderRoot()
    }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("parent_id", parentId)
        put("rows", rows); put("columns", columns)
        if (fitLocal) put("fit_local", true)
        putJsonArray("mesh_ids") { meshIds.forEach { add(JsonPrimitive(it)) } }
    }
    companion object {
        fun fromJson(o: JsonObject) = RigWarpEdit(o.text("id"), o.text("name"), o.text("parent_id"),
            requireNotNull(o["mesh_ids"]) { "mesh_ids is required" }.jsonArray.map { it.jsonPrimitive.content },
            o["rows"]?.jsonPrimitive?.int ?: 4, o["columns"]?.jsonPrimitive?.int ?: 4,
            o["fit_local"]?.jsonPrimitive?.boolean ?: false)
    }
}

private fun JsonObject.text(key: String) = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "$key is required" }
