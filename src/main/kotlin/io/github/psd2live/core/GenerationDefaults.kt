package io.github.psd2live.core

/** Defaults used by both generation editors and neutral document commands. */
fun PipelineConfig.defaultMeshSettings(tag: SemanticTag?): MeshSettings {
    val density = when (tag) {
        SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
        SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
        SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
        SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
        else -> 1f
    }
    return MeshSettings(meshOuterMargin, if (tag == SemanticTag.FACE) MeshEdgeMode.DOUBLE else meshEdgeMode,
        meshEdgeWidth, kotlin.math.max(12f, meshMaxEdgeDistance * density),
        kotlin.math.max(12f, meshInteriorDensity * density), meshFillAlgorithm,
        meshSuppressBoundaryDiagonals, meshFillParameters)
}

fun minimumAtlasSize(analysis: PipelineAnalysis?, scale: Int, padding: Int): Int {
    val valid = analysis?.layers?.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
        ?.takeIf { it.isNotEmpty() } ?: return 1024
    val largest = valid.maxOf { maxOf(it.source.raster.width * scale, it.source.raster.height * scale) + padding * 2 }
    var size = 256
    while (size < largest && size < 16384) size = size shl 1
    return size.coerceIn(256, 16384)
}
