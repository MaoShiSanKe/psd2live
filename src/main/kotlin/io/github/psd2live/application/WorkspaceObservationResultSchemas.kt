package io.github.psd2live.application

import kotlinx.serialization.json.*

/** Image bytes use native content; the structured result describes their identity and coordinate mapping. */
internal object WorkspaceObservationResultSchemas {
    private val s = WorkspaceResultSchema
    private val values = s.dictionary(s.number())
    private val ids = s.array(s.handle())
    private val rectangle = s.obj(listOf("left", "top", "right", "bottom").associateWith { s.number() })
    private val measuredRectangle = s.obj(listOf("left", "top", "right", "bottom", "width", "height").associateWith { s.number() })
    private val rangeIssue = s.obj(mapOf("id" to s.handle(), "value" to s.number(), "min" to s.number(), "max" to s.number()))
    private val spatialFields = linkedMapOf(
        "spatialReferenceId" to s.handle(), "coordinateSpace" to s.handle(), "pixelOrigin" to s.constant("top_left_edge"),
        "pixelWidth" to s.integer(1), "pixelHeight" to s.integer(1),
        "pixelAspectRatio" to buildJsonObject { put("type", "integer"); put("const", 1) },
        "colorSpace" to s.constant("sRGB"), "alphaMode" to s.constant("straight"),
        "canvasWidth" to s.number(), "canvasHeight" to s.number(),
        "canvasUnitsPerPixelX" to s.number(), "canvasUnitsPerPixelY" to s.number(),
        "requestedViewRect" to measuredRectangle, "viewRect" to measuredRectangle, "focusRect" to measuredRectangle,
        "focusLayerIds" to ids, "objectScale" to s.number(), "pixelToCanvas" to s.vector(6), "canvasToPixel" to s.vector(6),
        "placementRule" to s.constant("map_full_png_to_view_rect_preserve_aspect"),
    )
    private val spatial = s.obj(spatialFields, spatialFields.keys - setOf("focusRect", "objectScale"))
    private val viewFields = linkedMapOf(
        "viewId" to s.handle(), "revisionId" to s.handle(), "kind" to s.handle(), "mimeType" to s.constant("image/png"),
        "pngBytes" to s.integer(1), "sha256" to s.handle(), "originalWidth" to s.integer(1), "originalHeight" to s.integer(1),
        "renderedWidth" to s.integer(1), "renderedHeight" to s.integer(1), "scale" to s.number(),
        "appliedParameters" to values, "outOfRangeParameters" to s.array(rangeIssue), "includedLayerIds" to ids,
        "annotatedLayerIds" to ids, "annotatedDeformerIds" to ids, "annotatedPathIds" to ids,
        "annotatedPathWidth" to s.boolean(), "annotatedPathHardness" to s.boolean(), "annotatedPathRadius" to s.boolean(),
        "pointIndices" to s.boolean(), "objectIds" to ids, "canvasRect" to rectangle, "spatial" to spatial,
    )
    val view = s.obj(viewFields)

    private fun sheet(versions: Boolean, physics: Boolean): JsonObject {
        val tileFields = linkedMapOf("id" to s.handle(), "viewId" to s.handle(), "imageRect" to s.vector(4),
            "parameters" to values, "outOfRangeParameters" to s.array(rangeIssue)) +
            if (versions) mapOf("revisionId" to s.handle()) else emptyMap()
        val fields = linkedMapOf("physicsSimulated" to s.constant(physics), "comparison" to s.constant(if (versions) "versions" else "poses"),
            "width" to s.integer(1), "height" to s.integer(1), "canvasRect" to s.vector(4), "commonParameters" to values,
            "tiles" to s.array(s.obj(tileFields, tileFields.keys - "outOfRangeParameters"), 1, 9)) +
            if (versions) mapOf("states" to s.array(s.handle(), 1, 2)) else mapOf("revisionId" to s.handle())
        return s.obj(fields + if (physics) mapOf("backend" to s.handle(), "fps" to s.integer(15, 120),
            "sampleTimes" to s.array(s.number(), 1, 9), "ranges" to s.dictionary(s.vector(2))) else emptyMap())
    }

    private val coverageFields = linkedMapOf("pixelCount" to s.integer(1), "uncoveredPixelCount" to s.integer(0),
        "uncoveredFraction" to s.number(0, 1), "alphaThreshold" to s.integer(1, 255), "uncoveredPixelBounds" to s.vector(4),
        "assumption" to s.handle(), "scope" to s.handle())
    private val coverage = s.obj(coverageFields, coverageFields.keys - "uncoveredPixelBounds")

    fun forOperation(id: String): JsonObject? = when (id) {
        "view_render_layer", "view_render_context", "view_render_model" -> view
        "view_render_poses" -> sheet(versions = false, physics = false)
        "view_compare_history" -> sheet(versions = true, physics = false)
        "view_sample_motion" -> sheet(versions = false, physics = true)
        "view_check_coverage" -> s.obj(mapOf("view" to view, "coverage" to coverage))
        else -> null
    }
}
