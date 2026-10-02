package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
internal fun DepthSplitDialog(offer: PSD2LiveViewModel.DepthSplitOffer,
                             onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val source = offer.preview.rig.puppet.drawables.first { it.id.raw == offer.sourceId }
    val choices = remember(offer) { offer.preview.rig.puppet.drawables
        .filter { it.id != source.id && it.mesh != null }.sortedBy { it.name } }
    var middle by remember(offer) { mutableStateOf(choices.firstOrNull { it.id.raw == offer.initialMiddleId }) }
    Box(Modifier.fillMaxSize().background(colors.scrim).scrimDismiss(onDismiss = onDismiss), Alignment.Center) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(8.dp)).background(colors.panelElevated)
            .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
            .clickable(enabled = false) {}.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("editor.depthSplit.title"), style = typography.title, color = colors.textPrimary)
            Text(tr("editor.depthSplit.body", source.name), style = typography.body, color = colors.textPrimary)
            Text(tr("editor.depthSplit.middle"), style = typography.caption, color = colors.textMuted)
            CompactDropdown(items = listOf(null) + choices, selectedItem = middle, onItemSelected = { middle = it },
                itemLabel = { it?.name ?: tr("editor.depthSplit.choose") }, modifier = Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().background(colors.inputBackground, RoundedCornerShape(4.dp))
                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Top to bottom matches the visible draw-order stack in the hierarchy.
                listOf(
                    tr("editor.depthSplit.front") to tr("editor.depthSplit.frontName", source.name),
                    tr("editor.depthSplit.between") to (middle?.name ?: tr("editor.depthSplit.choose")),
                    tr("editor.depthSplit.back") to tr("editor.depthSplit.backName", source.name),
                ).forEach { (role, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(role, style = typography.caption, color = colors.textMuted, modifier = Modifier.width(40.dp))
                        Text(name, style = typography.body, color = colors.textPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text(tr("editor.depthSplit.hint"), style = typography.caption, color = colors.textMuted)
            if (choices.isEmpty()) Text(tr("editor.depthSplit.noMiddle"), style = typography.caption, color = colors.warning)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                CompactButton(tr("action.cancel"), onDismiss)
                CompactButton(tr("editor.depthSplit.confirm"), { middle?.let { onConfirm(it.id.raw) } },
                    enabled = middle != null, isPrimary = true)
            }
        }
    }
}
