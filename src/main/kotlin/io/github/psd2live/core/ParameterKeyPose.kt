package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** Evaluator tolerance and first-key tie breaking are shared by gesture and agent preparation. */
internal fun nearestKeyPose(axes: List<KeyformAxis>, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): Map<ParameterId, Float> =
    buildMap {
        for (axis in axes) {
            val current = pose[axis.parameterId] ?: defaults[axis.parameterId] ?: continue
            if (axis.keys.any { abs(it - current) < EPS_KEY }) continue
            axis.keys.minByOrNull { abs(it - current) }?.let { put(axis.parameterId, it) }
        }
    }
