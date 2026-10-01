package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.theme.LocalToolColors

@Composable
internal fun SkeletonTransformControls(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    var dx by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var dy by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var angle by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var scale by remember(editor.selectedBoneIds) { mutableStateOf(1.0) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("skeleton.transform.hint", editor.selectedBoneIds.size), color = colors.textMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(tr("skeleton.transform.all"), onClick = { editor.selectBones(editor.skeletonDraft?.bones?.mapTo(linkedSetOf()) { it.id }.orEmpty()) }, modifier = Modifier.weight(1f))
            CompactButton(tr("skeleton.transform.chain"), onClick = {
                val ids = editor.selectedBoneIds
                editor.selectBones(ids + ids.flatMap { editor.skeletonDraft?.descendants(it).orEmpty() })
            }, enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f))
        }
        CompactToggleChip(tr("skeleton.transform.children"), selected = editor.transformBoneDescendants,
            onToggle = { editor.transformBoneDescendants = !editor.transformBoneDescendants })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(dx, { dx = it }, min = -100000.0, unit = "X px", decimals = 1, modifier = Modifier.weight(1f))
            CompactNumberSpinner(dy, { dy = it }, min = -100000.0, unit = "Y px", decimals = 1, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(angle, { angle = it }, min = -360.0, max = 360.0, unit = "°", decimals = 1, modifier = Modifier.weight(1f))
            CompactNumberSpinner(scale, { scale = it }, min = 0.01, max = 100.0, step = 0.1, decimals = 2, unit = "×", modifier = Modifier.weight(1f))
        }
        CompactButton(tr("skeleton.transform.apply"), enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            onClick = { editor.transformSelectedBones(dx.toFloat(), dy.toFloat(), angle.toFloat(), scale.toFloat()); dx = 0.0; dy = 0.0; angle = 0.0; scale = 1.0 })
    }
}
