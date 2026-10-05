package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.theme.LocalToolColors

@Composable
internal fun SkeletonIkControls(editor: CanvasEditor) {
    val spec = editor.skeletonDraft ?: editor.committedSkeleton ?: return
    val bone = spec.bone(editor.selectedBoneId ?: "") ?: return
    val colors = LocalToolColors.current
    val settings = bone.ik
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("skeleton.ik.settings", bone.name), color = colors.textPrimary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(settings.chainLength.toDouble(), { editor.setSelectedBoneIk(settings.copy(chainLength = it.toInt())) },
                min = 1.0, max = 32.0, unit = tr("skeleton.ik.chain"), modifier = Modifier.weight(1f))
            CompactNumberSpinner(settings.iterations.toDouble(), { editor.setSelectedBoneIk(settings.copy(iterations = it.toInt())) },
                min = 1.0, max = 256.0, unit = tr("skeleton.ik.iterations"), modifier = Modifier.weight(1f))
        }
        CompactNumberSpinner(settings.tolerancePx.toDouble(), { editor.setSelectedBoneIk(settings.copy(tolerancePx = it.toFloat())) },
            min = 0.001, max = 10.0, step = 0.01, decimals = 3, unit = tr("skeleton.ik.tolerance"), modifier = Modifier.fillMaxWidth())
        val bends = listOf(0 to tr("skeleton.ik.bend.auto"), 1 to tr("skeleton.ik.bend.clockwise"), -1 to tr("skeleton.ik.bend.counterclockwise"))
        CompactDropdown(bends, bends.first { it.first == settings.bendDirection }, { editor.setSelectedBoneIk(settings.copy(bendDirection = it.first)) },
            itemLabel = { it.second }, modifier = Modifier.fillMaxWidth())
        if (editor.skeletonDraft == null) {
            val target = spec.ikTargets[bone.id]
            if (target == null) CompactButton(tr("skeleton.ik.pin"), { editor.pinSelectedBone() }, enabled = !bone.role.anchor, modifier = Modifier.fillMaxWidth())
            else {
                CompactToggleChip(tr("skeleton.ik.enabled"), target.enabled, { editor.updateIkTarget(bone.id, target.copy(enabled = !target.enabled)) })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactNumberSpinner(target.x.toDouble(), { editor.updateIkTarget(bone.id, target.copy(x = it.toFloat())) },
                        min = -100000.0, decimals = 1, unit = "X px", modifier = Modifier.weight(1f))
                    CompactNumberSpinner(target.y.toDouble(), { editor.updateIkTarget(bone.id, target.copy(y = it.toFloat())) },
                        min = -100000.0, decimals = 1, unit = "Y px", modifier = Modifier.weight(1f))
                }
                val posed = editor.posedBones().firstOrNull { it.bone.id == bone.id }
                val error = posed?.let { kotlin.math.hypot(it.tailX - target.x, it.tailY - target.y) } ?: 0f
                Text(tr("skeleton.ik.error", "%.2f".format(error)), color = if (error > settings.tolerancePx) colors.warning else colors.textMuted, fontSize = 10.sp)
                CompactButton(tr("skeleton.ik.release"), { editor.updateIkTarget(bone.id, null) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(tr("skeleton.ik.hint"), color = colors.textMuted, fontSize = 10.sp)
    }
}
