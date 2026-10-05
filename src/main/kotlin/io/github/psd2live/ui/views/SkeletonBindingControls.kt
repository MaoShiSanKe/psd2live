package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.theme.LocalToolColors

@Composable
internal fun SkeletonBindingControls(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    val spec = editor.skeletonDraft ?: return
    val bone = spec.bone(editor.selectedBoneId ?: "")
    Text(tr("skeleton.bind.preview", editor.pendingSkeletonDrawableIds.size, bone?.name ?: "—"), color = colors.textMuted, fontSize = 10.sp)
    val drawables = editor.model.drawables.filter { it.mesh != null }
    Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
        for (drawable in drawables) Row(verticalAlignment = Alignment.CenterVertically) {
            CompactCheckbox(drawable.id.raw in editor.pendingSkeletonDrawableIds, { editor.toggleSkeletonBindingDrawable(drawable.id.raw) })
            Text(drawable.name, color = colors.textPrimary, fontSize = 10.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactButton(tr("skeleton.bind.apply"), { editor.applySkeletonBindingBatch() }, modifier = Modifier.weight(1f),
            enabled = bone != null && editor.pendingSkeletonDrawableIds.isNotEmpty())
        CompactButton(tr("skeleton.bind.unbind"), { editor.applySkeletonBindingBatch(unbind = true) }, modifier = Modifier.weight(1f),
            enabled = editor.pendingSkeletonDrawableIds.isNotEmpty())
    }
    CompactButton(tr("skeleton.bind.clear"), { editor.selectSkeletonBindingDrawables(emptySet()) }, modifier = Modifier.fillMaxWidth())
}
