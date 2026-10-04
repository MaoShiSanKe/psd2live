package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.application.WorkspacePaintSession
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.min

internal data class PaintStrokeRecord(val id: String, val name: String)

/** Compose projection of the application-owned raster draft and stroke history. */
class PaintSession(val handle: WorkspacePaintSession) {
    val layerId get() = handle.layerId
    val layerName get() = handle.layerName
    val docWidth get() = handle.width
    val docHeight get() = handle.height
    /** Detached observation; writes must use the shared session gestures. */
    val workingImage: BufferedImage get() = handle.image()
    var isDirty by mutableStateOf(false)
        private set
    internal val strokeRecords = mutableStateListOf<PaintStrokeRecord>()
    var currentStrokeIndex by mutableStateOf(0)
        private set
    val strokeCount get() = strokeRecords.size - 1
    var previewTiles by mutableStateOf<List<PreviewTile>>(emptyList())
        private set
    class PreviewTile(val x: Int, val y: Int, val width: Int, val height: Int, val image: ImageBitmap)
    private val published = HashMap<Long, PreviewTile>()
    private val stale = HashSet<Long>()
    private val detach: () -> Unit
    init {
        detach = handle.observe { regions ->
            regions.forEach(::markDirty)
            sync()
            if (!handle.activeStroke) refreshPreview()
        }
        sync(); markDirty(Rectangle(0, 0, docWidth, docHeight)); refreshPreview()
    }
    private fun sync() {
        isDirty = handle.isDirty && !handle.finished
        currentStrokeIndex = handle.index
        val next = handle.strokes.map { PaintStrokeRecord(it.id,
            if (it.id == "init") tr("editor.paint.strokeInitial") else it.name) }
        if (next != strokeRecords.toList()) { strokeRecords.clear(); strokeRecords.addAll(next) }
    }
    private fun markDirty(rect: Rectangle) {
        val area = rect.intersection(Rectangle(0, 0, docWidth, docHeight))
        if (area.isEmpty) return
        for (ty in area.y / PREVIEW_TILE until (area.y + area.height + PREVIEW_TILE - 1) / PREVIEW_TILE)
            for (tx in area.x / PREVIEW_TILE until (area.x + area.width + PREVIEW_TILE - 1) / PREVIEW_TILE)
                stale += (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
    }
    fun refreshPreview() {
        if (stale.isEmpty()) return
        for (key in stale.toList()) {
            val x = (key ushr 32).toInt() * PREVIEW_TILE
            val y = (key and 0xFFFFFFFFL).toInt() * PREVIEW_TILE
            val width = min(PREVIEW_TILE, docWidth - x); val height = min(PREVIEW_TILE, docHeight - y)
            if (width > 0 && height > 0) published[key] =
                PreviewTile(x, y, width, height, handle.tile(x, y, width, height).toComposeImageBitmap())
        }
        stale.clear(); previewTiles = published.values.toList()
    }
    internal fun beginStroke() = handle.beginStroke()
    internal fun segment(x0: Float, y0: Float, x1: Float, y1: Float, tip: RasterPaintEngine.Tip,
        color: Color, opacity: Float, erase: Boolean) = handle.segment(x0, y0, x1, y1, tip, color.toArgb(), opacity, erase)
    internal fun abandonStroke() = handle.abandonStroke()
    fun recordStroke(name: String) = handle.recordStroke(name)
    fun canUndo() = handle.canUndo()
    fun canRedo() = handle.canRedo()
    fun undo() = handle.undo()
    fun redo() = handle.redo()
    fun jumpToStroke(index: Int) = handle.jump(index)
    fun discard() { handle.cancel(); detach() }
    fun dismiss() { handle.dismiss(); detach() }
    fun sample(x: Int, y: Int) = handle.sample(x, y)
    fun clear(name: String) = handle.gesture(buildJsonObject { put("mode", "clear") }, name)
    fun bucket(x: Int, y: Int, color: Color, tolerance: Int, name: String) =
        handle.gesture(buildJsonObject {
            put("mode", "bucket"); put("point", JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))
            put("tolerance", tolerance); put("color", rgba(color))
        }, name)
    fun shape(x0: Int, y0: Int, x1: Int, y1: Int, shape: PaintShape, color: Color, opacity: Float,
        strokeWidth: Float, filled: Boolean, name: String) = handle.gesture(buildJsonObject {
            put("mode", "shape"); put("from", JsonArray(listOf(JsonPrimitive(x0), JsonPrimitive(y0))))
            put("to", JsonArray(listOf(JsonPrimitive(x1), JsonPrimitive(y1)))); put("shape", shape.name.lowercase())
            put("color", rgba(color)); put("opacity", opacity); put("stroke_width", strokeWidth); put("filled", filled)
        }, name)
    private fun rgba(color: Color): JsonArray {
        val argb = color.toArgb()
        return JsonArray(listOf(argb ushr 16 and 255, argb ushr 8 and 255, argb and 255, argb ushr 24 and 255).map(::JsonPrimitive))
    }
    companion object { const val PREVIEW_TILE = 512 }
}
