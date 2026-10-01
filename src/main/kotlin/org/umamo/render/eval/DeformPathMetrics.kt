package org.umamo.render.eval

import org.umamo.runtime.eval.meshGridDefaultDeltas

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/** Internal coordinate scale for deformation math and migration of old path journals. */
object DeformPathMetrics {
    fun canvasScale(model: PuppetModel, drawable: Drawable): Float {
        val mesh = requireNotNull(drawable.mesh)
        val deltas = meshGridDefaultDeltas(drawable) { id ->
            model.parameters.firstOrNull { it.id == id }?.default ?: 0f
        }
        val local = FloatArray(mesh.positions.size) { mesh.positions[it] + (deltas?.get(it) ?: 0f) }
        // Rest meshes may be local (new PSD rigs) or canvas-based (CMO3 imports).
        // Only the actual parent transform gives a scale valid in both representations.
        val canvas = drawableSpaceMapping(model, emptyMap(), drawable.id)?.localToWorld(local) ?: return 1f
        var canvasLength = 0.0
        var localLength = 0.0
        for (i in mesh.indices.indices step 3) for (j in 0..2) {
            val a = mesh.indices[i + j] * 2
            val b = mesh.indices[i + (j + 1) % 3] * 2
            canvasLength += hypot((canvas[a] - canvas[b]).toDouble(), (canvas[a + 1] - canvas[b + 1]).toDouble())
            localLength += hypot((local[a] - local[b]).toDouble(), (local[a + 1] - local[b + 1]).toDouble())
        }
        return if (localLength > 1e-12 && canvasLength > 1e-12) (canvasLength / localLength).toFloat() else 1f
    }

}
