package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Existing axes and the panel's selected inputs address the same pose for every deformation tool. */
internal fun canvasDeformationCoordinate(
    model: PuppetModel,
    axes: List<KeyformAxis>,
    pose: Map<String, Float>,
    selected: List<String>,
): Map<String, Float> = buildMap {
    val parameters = model.parameters.associateBy { it.id.raw }
    for (id in axes.map { it.parameterId.raw } + selected) {
        val p = parameters[id] ?: continue
        if (p.kind == ParameterKind.NORMAL) put(id, pose[id] ?: p.default)
    }
    val active = model.parameters.filter { it.kind == ParameterKind.BLEND_SHAPE && abs(pose[it.id.raw] ?: it.default) >= EPS_KEY }
    val chosen = active.firstOrNull { it.id.raw in selected } ?: active.singleOrNull()
    chosen?.let { put(it.id.raw, pose[it.id.raw] ?: it.default) }
}
