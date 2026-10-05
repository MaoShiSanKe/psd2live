package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconSnapshot
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.project.ParameterSnapshot
import io.github.psd2live.ui.state.ParameterSnapshotPreview
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/**
 * One-line bar of parameter snapshots: + saves the current pose, hover ghosts it on the canvas, a click loads one,
 * right-click overwrites, renames or deletes it, and a middle click deletes it at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ParameterSnapshotBar(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	renamingId: String?,
	onRenamingChange: (String?) -> Unit,
) {
	val colors = LocalToolColors.current
	val hasModel = state.previewModel != null
	val previewContext = listOf(state.projectOpenGeneration, state.activeWorkspace.id, state.activeCanvas.id)
	Row(
		modifier = Modifier.fillMaxWidth().height(22.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(3.dp),
	) {
		TooltipArea(tooltip = { ParameterTooltip(tr("parameters.snapshotsHint")) }, delayMillis = 400) {
			Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
				IconSnapshot(modifier = Modifier.size(12.dp), tint = colors.textMuted)
			}
		}
		Row(
			modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			for (snapshot in state.parameterSnapshots) {
				key(snapshot.id) {
					ParameterSnapshotChip(
						snapshot = snapshot,
						enabled = hasModel,
						renaming = renamingId == snapshot.id,
						onApply = { viewModel.applyParameterSnapshot(snapshot.id) },
						onOverwrite = { viewModel.overwriteParameterSnapshot(snapshot.id) },
						onStartRename = { onRenamingChange(snapshot.id) },
						onRename = { name ->
							viewModel.renameParameterSnapshot(snapshot.id, name)
							onRenamingChange(null)
						},
						onCancelRename = { onRenamingChange(null) },
						onDelete = { viewModel.deleteParameterSnapshot(snapshot.id) },
						previewContext = previewContext,
						onPreview = { viewModel.previewParameterSnapshot(snapshot.id) },
						onClearPreview = viewModel::clearParameterSnapshotPreview,
					)
				}
			}
		}
		CompactIconButton(
			onClick = { viewModel.saveParameterSnapshot() },
			enabled = hasModel,
			size = 22.dp,
			tooltip = tr("parameters.snapshotSave"),
		) {
			IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ParameterSnapshotChip(
	snapshot: ParameterSnapshot,
	enabled: Boolean,
	renaming: Boolean,
	onApply: () -> Unit,
	onOverwrite: () -> Unit,
	onStartRename: () -> Unit,
	onRename: (String) -> Unit,
	onCancelRename: () -> Unit,
	onDelete: () -> Unit,
	previewContext: List<Any>,
	onPreview: () -> ParameterSnapshotPreview?,
	onClearPreview: (ParameterSnapshotPreview) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	DisposableEffect(hovered, enabled, renaming, menuOpen, previewContext) {
		val preview = if (hovered && enabled && !renaming && !menuOpen) onPreview() else null
		onDispose { if (preview != null) onClearPreview(preview) }
	}
	Box {
		if (renaming) {
			var draft by remember { mutableStateOf(snapshot.name) }
			var settled by remember { mutableStateOf(false) }
			val focus = remember { FocusRequester() }
			LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
			fun commit() {
				if (settled) return
				settled = true
				onRename(draft)
			}
			CompactTextField(
				value = draft,
				onValueChange = { draft = it },
				placeholder = "${snapshot.number}",
				modifier = Modifier
					.width(96.dp)
					.focusRequester(focus)
					.onPreviewKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
							settled = true
							onCancelRename()
							true
						} else false
					},
				height = 20.dp,
				selectAllOnFocus = true,
				endEditOnSettle = false,
				onCommit = ::commit,
				onFocusLost = ::commit,
			)
		} else {
			TooltipArea(tooltip = { ParameterTooltip(snapshot.name.ifBlank { tr("parameters.snapshotName", snapshot.number) }) }, delayMillis = 400) {
				Box(
					modifier = Modifier
						.height(18.dp)
						.widthIn(min = 18.dp)
						.background(if (hovered && enabled) colors.controlHover else colors.controlBackground, RoundedCornerShape(3.dp))
						.border(BorderStroke(1.dp, if (hovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(3.dp))
						.hoverable(interaction)
						.onPointerEvent(PointerEventType.Press) { event ->
							when (event.button) {
								PointerButton.Secondary -> {
									menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
									menuOpen = true
									event.changes.forEach { it.consume() }
								}
								PointerButton.Tertiary -> {
									onDelete()
									event.changes.forEach { it.consume() }
								}
								else -> Unit
							}
						}
						.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onApply)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.padding(horizontal = 4.dp),
					contentAlignment = Alignment.Center,
				) {
					val named = snapshot.name.isNotBlank()
					Text(
						text = if (named) snapshot.name else "${snapshot.number}",
						style = if (named) typography.caption.copy(fontSize = 10.sp)
						else typography.caption.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
						color = if (enabled) colors.textPrimary else colors.textDisabled,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.widthIn(max = 96.dp),
					)
				}
			}
		}
		TreeContextMenu(
			expanded = menuOpen,
			onDismissRequest = { menuOpen = false },
			clickOffset = menuOffset,
			minWidth = 160.dp,
		) {
			CompactMenuItem(
				text = tr("parameters.snapshotOverwrite"),
				onClick = { menuOpen = false; onOverwrite() },
				enabled = enabled,
			)
			CompactMenuItem(
				text = tr("parameters.snapshotRename"),
				onClick = { menuOpen = false; onStartRename() },
			)
			CompactMenuDivider()
			CompactMenuItem(
				text = tr("parameters.snapshotDelete"),
				onClick = { menuOpen = false; onDelete() },
				danger = true,
			)
		}
	}
}
