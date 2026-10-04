package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RasterMeshJournal
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.math.abs

/** Compare neutral geometry with its texture coordinates, so a same-bounds reflection cannot pass. */
internal fun validateRegisteredNeutral(preview: RigPreviewModel, layerIds: Set<String>) {
    if (layerIds.isEmpty()) return
    val evaluated = CpuDeformationEvaluator().evaluate(preview.rig.puppet, emptyMap())
    for (drawable in preview.rig.puppet.drawables) {
        val layerId = preview.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
        if (layerId !in layerIds) continue
        val uv = requireNotNull(drawable.mesh).uvs
        val canvas = RasterMeshJournal.TextureCoordinates(preview.rig.puppet, drawable).toCanvas(uv)
        val actual = requireNotNull(evaluated.worldPositions[drawable.id])
        require(actual.size == uv.size)
        for (i in actual.indices step 2) {
            val x = canvas[i]
            val y = canvas[i+1]
            require(actual[i].isFinite() && actual[i+1].isFinite() && abs(actual[i]-x) < 2f && abs(-actual[i+1]-y) < 2f) {
                "Neutral placement drift/reflection for $layerId at vertex ${i/2}. Inspect source raster, parent ${drawable.parentDeformerId?.raw}, geometry and flip channels. No commit was made; original asset and registration remain recoverable."
            }
        }
    }
}
