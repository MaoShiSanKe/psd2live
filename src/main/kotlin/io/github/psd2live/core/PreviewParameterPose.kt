package io.github.psd2live.core

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId

/** Bound the composed pose, after motion, tracking and physics have contributed. */
internal fun boundedPreviewPose(values: Map<ParameterId, Float>, parameters: List<Parameter>): Map<ParameterId, Float> {
    var bounded: MutableMap<ParameterId, Float>? = null
    for (parameter in parameters) {
        val value = values[parameter.id] ?: continue
        val next = if (value.isFinite()) value.coerceIn(parameter.min, parameter.max) else parameter.default
        if (next != value) {
            if (bounded == null) bounded = values.toMutableMap()
            bounded[parameter.id] = next
        }
    }
    return bounded ?: values
}

/** Tracking owns its axes; an old inspector pose must not be added to the look target. */
internal fun pointerPreviewPose(
    values: Map<ParameterId, Float>, x: Float, y: Float, parameters: List<Parameter>,
    tracking: Boolean, locked: Set<ParameterId> = emptySet(),
): Map<ParameterId, Float> {
    if (!tracking) return boundedPreviewPose(values, parameters)
    val pose = values.toMutableMap()
    val byId = parameters.associateBy { it.id }
    for (binding in CUBISM_POINTER_TRACKING_BINDINGS) {
        val id = ParameterId(binding.parameterId)
        if (id in locked) continue
        val parameter = byId[id] ?: continue
        pose[id] = parameter.default + x * binding.xScale + y * binding.yScale
    }
    return boundedPreviewPose(pose, parameters)
}
