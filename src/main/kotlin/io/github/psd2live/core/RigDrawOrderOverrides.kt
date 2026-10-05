package io.github.psd2live.core

import org.umamo.runtime.model.*

/** The ruler's explicit layer order wins over evaluated keys until the override is reset. */
internal fun BuiltRig.withDrawOrderOverrides(overrides: Map<String, Float>): BuiltRig {
    if (overrides.isEmpty()) return this
    var changed = false
    val drawables = puppet.drawables.map { drawable ->
        val order = overrides[layerIdByDrawableId[drawable.id.raw]] ?: overrides[drawable.id.raw] ?: return@map drawable
        require(order.isFinite() && order in 0f..1000f) { "Draw order must be within 0..1000" }
        changed = true
        drawable.copy(drawOrder = order,
            channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel - FormChannel.DRAW_ORDER),
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, it.opacity, it.multiplyColor, it.screenColor) }
            }) })
    }
    return if (!changed) this else copy(puppet = puppet.copy(drawables = drawables).withDerivedRenderRoot())
}
