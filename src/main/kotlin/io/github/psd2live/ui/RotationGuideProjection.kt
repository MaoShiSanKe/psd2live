package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.RenderPoint
import io.github.psd2live.core.RotationGuideFrame
import org.umamo.render.eval.DrawableSpaceMapping

/** Compose coordinates adapt the shared rotation-guide frame. */
internal class RotationGuideProjection(origin: Offset, mapping: DrawableSpaceMapping,
    projectWorld: (Float, Float) -> Offset) {
    private val frame = RotationGuideFrame(RenderPoint(origin.x, origin.y), mapping) { x, y ->
        projectWorld(x, y).let { RenderPoint(it.x, it.y) }
    }
    fun toScreen(point: Offset): Offset = frame.toScreen(RenderPoint(point.x, point.y)).let { Offset(it.x, it.y) }
    fun toLocal(point: Offset): Offset = frame.toLocal(RenderPoint(point.x, point.y)).let { Offset(it.x, it.y) }
}
