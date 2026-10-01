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
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors

@Composable
internal fun SkeletonSavedPoseControls(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    var name by remember { mutableStateOf("") }
    val names = editor.committedSkeleton?.savedPoses?.keys.orEmpty().toList()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("skeleton.saved.hint"), color = colors.textMuted, fontSize = 10.sp)
        CompactTextField(name, { name = it }, placeholder = tr("skeleton.saved.name"), modifier = Modifier.fillMaxWidth())
        CompactButton(tr("skeleton.saved.save"), { name = name.trim(); editor.saveSkeletonPose(name) }, enabled = name.isNotBlank() && !editor.busy, modifier = Modifier.fillMaxWidth())
        if (names.isNotEmpty()) {
            val selected = name.takeIf { it in names } ?: names.first()
            CompactDropdown(names, selected, { name = it }, itemLabel = { it }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactButton(tr("skeleton.saved.apply"), { editor.applySavedSkeletonPose(selected) }, enabled = !editor.busy, modifier = Modifier.weight(1f))
                CompactButton(tr("skeleton.saved.delete"), { editor.deleteSavedSkeletonPose(selected) }, enabled = !editor.busy, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun InsertSavedSkeletonPoseMenu(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
    val items = listOf("" to tr("skeleton.saved.insert")) + state.rigEdits.skeleton?.savedPoses?.keys.orEmpty().map { it to it }
    CompactDropdown(items, items.first(), { if (it.first.isNotEmpty()) viewModel.insertSavedSkeletonPose(it.first) }, itemLabel = { it.second },
        enabled = items.size > 1, height = 22.dp, modifier = Modifier.width(125.dp))
}
