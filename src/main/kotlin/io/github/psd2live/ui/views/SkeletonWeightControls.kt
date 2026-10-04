package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.SkeletonManualWeights
import io.github.psd2live.core.SkeletonWeightBrushMode
import io.github.psd2live.core.SkeletonWeightTransferMode
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.theme.LocalToolColors

@Composable
internal fun SkeletonWeightControls(editor: CanvasEditor) {
    val spec = editor.skeletonDraft ?: return
    val colors = LocalToolColors.current
    val ids = spec.bones.filterNot { it.role.anchor || it.role.body }.flatMap { it.drawableIds }.toSet()
    val drawables = editor.model.drawables.filter { it.id.raw in ids && it.mesh != null }
    val options = listOf("" to tr("skeleton.weights.pick")) + drawables.map { it.id.raw to it.name }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactDropdown(options, options.firstOrNull { it.first == editor.skeletonWeightDrawableId } ?: options.first(),
            { editor.selectSkeletonWeightDrawable(it.first.takeIf(String::isNotEmpty)) }, itemLabel = { it.second }, modifier = Modifier.fillMaxWidth())
        CompactDropdown(SkeletonWeightBrushMode.entries, editor.skeletonWeightBrushMode, { editor.skeletonWeightBrushMode = it },
            itemLabel = { tr("skeleton.weights.mode.${it.name.lowercase()}") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(editor.skeletonWeightRadius.toDouble(), { editor.skeletonWeightRadius = it.toFloat() }, min = 1.0, max = editor.brushSizeLimit.toDouble(),
                unit = tr("skeleton.weights.radius"), modifier = Modifier.weight(1f))
            CompactNumberSpinner(editor.skeletonWeightStrength.toDouble(), { editor.skeletonWeightStrength = it.toFloat() }, min = 0.0, max = 1.0, step = 0.05,
                decimals = 2, unit = tr("skeleton.weights.strength"), modifier = Modifier.weight(1f))
        }
        if (editor.skeletonWeightBrushMode == SkeletonWeightBrushMode.REPLACE)
            CompactNumberSpinner(editor.skeletonWeightReplaceValue.toDouble(), { editor.skeletonWeightReplaceValue = it.toFloat() }, min = 0.0, max = 1.0,
                step = 0.05, decimals = 2, unit = tr("skeleton.weights.value"), modifier = Modifier.fillMaxWidth())
        val id = editor.skeletonWeightDrawableId
        val activeTree = id?.let { SkeletonManualWeights.treeIds(spec, it) }.orEmpty()
        val canPaint = editor.selectedBoneId in activeTree
        Text(tr(if (canPaint) "skeleton.weights.ready" else "skeleton.weights.needBone"), color = if (canPaint) colors.textMuted else colors.warning, fontSize = 10.sp)
        val stored = spec.manualWeights[id]
        val parents = io.github.psd2live.core.SkeletonRig.jointParents(spec).mapValues { it.value?.id }
        val invalid = stored?.weights?.count { row -> row.isEmpty() || row.size > editor.skeletonWeightInfluences ||
            kotlin.math.abs(row.values.sum() - 1f) > 0.001f || row.keys.any { it !in activeTree } || row.values.any { it < editor.skeletonWeightCutoff } ||
            (row.size == 2 && row.keys.none { parents[it] in row.keys }) } ?: 0
        Text(tr("skeleton.weights.stats", stored?.weights?.size ?: 0, invalid), color = colors.textMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(editor.skeletonWeightInfluences.toDouble(), { editor.skeletonWeightInfluences = it.toInt() }, min = 1.0, max = 2.0,
                unit = tr("skeleton.weights.influences"), modifier = Modifier.weight(1f))
            CompactNumberSpinner(editor.skeletonWeightCutoff.toDouble(), { editor.skeletonWeightCutoff = it.toFloat() }, min = 0.0, max = 1.0,
                step = 0.001, decimals = 3, unit = tr("skeleton.weights.cutoff"), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(tr("skeleton.weights.clean"), { editor.cleanupSkeletonWeights() }, enabled = id != null, modifier = Modifier.weight(1f))
            CompactButton(tr("skeleton.weights.auto"), { editor.resetSkeletonWeights() }, enabled = id != null, modifier = Modifier.weight(1f))
        }
        Text(tr("skeleton.weights.copy"), color = colors.textPrimary, fontSize = 11.sp)
        CompactDropdown(options, options.firstOrNull { it.first == editor.skeletonWeightSourceId } ?: options.first(),
            { editor.skeletonWeightSourceId = it.first.takeIf(String::isNotEmpty) }, itemLabel = { it.second }, modifier = Modifier.fillMaxWidth())
        CompactDropdown(SkeletonWeightTransferMode.entries, editor.skeletonWeightTransferMode, { editor.skeletonWeightTransferMode = it },
            itemLabel = { tr("skeleton.weights.transfer.${it.name.lowercase()}") }, modifier = Modifier.fillMaxWidth())
        CompactToggleChip(tr("skeleton.weights.mirror"), editor.mirrorSkeletonWeights, { editor.mirrorSkeletonWeights = !editor.mirrorSkeletonWeights })
        if (editor.mirrorSkeletonWeights) CompactNumberSpinner((spec.symmetryAxisX ?: editor.model.canvasWidth / 2f).toDouble(),
            { editor.setBoneSymmetryAxis(it.toFloat()) }, min = -100000.0, decimals = 1, unit = tr("skeleton.structure.axis"), modifier = Modifier.fillMaxWidth())
        if (editor.skeletonWeightTransferMode == SkeletonWeightTransferMode.NEAREST)
            CompactNumberSpinner(editor.skeletonWeightTransferTolerance.toDouble(), { editor.skeletonWeightTransferTolerance = it.toFloat() },
                min = 0.0, max = 10000.0, decimals = 1, unit = tr("skeleton.weights.distance"), modifier = Modifier.fillMaxWidth())
        val targetIds = id?.let { SkeletonManualWeights.treeIds(spec, it) }.orEmpty()
        val sourceIds = editor.skeletonWeightSourceId?.let { SkeletonManualWeights.treeIds(spec, it) }.orEmpty()
        val mapping = editor.effectiveSkeletonWeightBoneMapping()
        val targets = listOf("" to tr("skeleton.weights.unassigned")) + targetIds.map { it to (spec.bone(it)?.name ?: it) }
        for (source in sourceIds) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.bone(source)?.name ?: source, modifier = Modifier.weight(0.4f), color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompactDropdown(targets, targets.firstOrNull { it.first == mapping[source] } ?: targets.first(),
                { editor.setSkeletonWeightBoneMapping(source, it.first) }, itemLabel = { it.second }, modifier = Modifier.weight(0.6f))
        }
        val transfer = remember(spec, editor.skeletonWeightSourceId, id, editor.skeletonWeightTransferMode, editor.skeletonWeightTransferTolerance,
            editor.mirrorSkeletonWeights, editor.skeletonWeightBoneMapping, editor.model) { editor.skeletonWeightTransferPreview() }
        Text(if (transfer == null) tr("skeleton.weights.noTransfer") else tr("skeleton.weights.preview", transfer.matched, transfer.unmatched), color = colors.textMuted, fontSize = 10.sp)
        CompactButton(tr("skeleton.weights.applyCopy"), { editor.applySkeletonWeightTransfer() }, enabled = transfer != null && transfer.matched > 0, modifier = Modifier.fillMaxWidth())
    }
}
