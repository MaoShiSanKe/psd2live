package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.BakeShape
import io.github.psd2live.core.BakeWrite
import io.github.psd2live.core.PoseEase
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.BakePoseRow
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val BAKE_FIELD = "skeleton-bake-field"

/** A clip the bake can write into, or a new one when [id] is null. */
private data class ClipChoice(val id: String?, val label: String)

private data class IkChoice(val boneId: String?, val label: String)

/**
 * Bakes a sequence of skeleton poses into a motion clip. Poses are captured from the canvas one at a time,
 * each with its own time and ease, and may pull a bone's tip to a point (IK) instead of holding the FK
 * angles. The result is solved per frame, thinned to the tolerance, previewed on the canvas and written
 * to the chosen clip as a single undoable change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkeletonBakeDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val bake = viewModel.skeletonBake
	val bones = remember(state.rigEdits.skeleton) { state.rigEdits.skeleton?.bones.orEmpty().filterNot { it.role.anchor } }
	val ikBones = remember(state.rigEdits.skeleton) { viewModel.bakeIkBones() }
	val clips = state.rigEdits.motionClips
	val presets = state.rigEdits.posePresets
	val preview = bake.preview
	val result = preview?.result

	// The preview scrubber plays on its own clock, posing the canvas from the bake that is not yet written.
	LaunchedEffect(bake.previewPlaying, result) {
		if (!bake.previewPlaying || result == null) return@LaunchedEffect
		var last = System.nanoTime()
		if (bake.previewTime >= result.end - 1e-4f) viewModel.previewBakeAt(result.start)
		while (bake.previewPlaying) {
			delay(16)
			val now = System.nanoTime()
			val next = bake.previewTime + (now - last) / 1e9f
			last = now
			if (next >= result.end) {
				viewModel.previewBakeAt(result.end)
				bake.previewPlaying = false
			} else viewModel.previewBakeAt(next)
		}
	}

	Box(
		modifier = Modifier.fillMaxSize().background(colors.scrim).scrimDismiss(onDismiss = viewModel::closeSkeletonBake),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(660.dp)
				.clip(RoundedCornerShape(8.dp))
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					IconBone(modifier = Modifier.size(14.dp), tint = colors.accent)
					Text(
						tr("animation.bake.title"),
						style = typography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
				}
				Text(
					"✕", style = typography.caption.copy(fontSize = 14.sp), color = colors.textMuted,
					modifier = Modifier.clickable(onClick = viewModel::closeSkeletonBake).padding(4.dp),
				)
			}

			Column(
				Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				// --- Pose sequence -------------------------------------------------------------
				BakeSection(tr("animation.bake.sequence")) {
					Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						CompactButton(
							text = tr("animation.bake.capture"),
							onClick = { viewModel.captureBakeRow() },
							leadingIcon = { IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary) },
							height = 22.dp,
						)
						Text(tr("animation.bake.captureTip"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
					}
					if (bake.rows.isEmpty()) {
						Text(tr("animation.bake.empty"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
					} else {
						Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Gap(20)
							HeaderCell(tr("animation.bake.colTime"), 86)
							HeaderCell(tr("animation.bake.colMode"), 150)
							HeaderCell(tr("animation.bake.colEase"), 96)
						}
						bake.rows.forEachIndexed { index, row ->
							BakeRowItem(index, row, ikBones, viewModel)
						}
					}
				}

				// --- Pose snapshots ------------------------------------------------------------
				var snapshotsOpen by remember { mutableStateOf(false) }
				BakeSection(
					tr("animation.bake.snapshots", presets.size),
					onHeaderClick = { snapshotsOpen = !snapshotsOpen },
					expanded = snapshotsOpen,
				) {
					if (snapshotsOpen) {
						CompactButton(
							text = tr("animation.bake.snapshotSaveCurrent"),
							onClick = { viewModel.savePosePreset("", viewModel.capturePoseValues()) },
							leadingIcon = { IconSnapshot(modifier = Modifier.size(10.dp), tint = colors.textPrimary) },
							height = 22.dp,
						)
						if (presets.isEmpty()) {
							Text(tr("animation.bake.snapshotsEmpty"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
						}
						presets.forEach { preset ->
							Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Text(preset.name, style = typography.body.copy(fontSize = 12.sp), color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
								CompactButton(tr("animation.bake.snapshotInsert"), onClick = { viewModel.insertPosePresetAsBakeRow(preset.id) }, height = 20.dp)
								CompactButton(tr("animation.bake.snapshotApply"), onClick = { viewModel.applyPosePreset(preset.id) }, height = 20.dp)
								CompactIconButton(onClick = { viewModel.deletePosePreset(preset.id) }, tooltip = tr("animation.bake.removeKey"), size = 20.dp) {
									IconTrash(modifier = Modifier.size(11.dp))
								}
							}
						}
					}
				}

				// --- Range ---------------------------------------------------------------------
				BakeSection(tr("animation.bake.range")) {
					val selected = bake.boneIds
					FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						CompactToggleChip(
							text = if (selected == null) tr("animation.bake.allBones") else tr("animation.bake.selectedBones", selected.size, bones.size),
							selected = selected == null,
							onToggle = { bake.boneIds = null; viewModel.refreshBakePreview() },
							height = 20.dp,
						)
						bones.forEach { bone ->
							CompactToggleChip(
								text = bone.name,
								selected = selected == null || bone.id in selected,
								onToggle = {
									val all = bones.map { it.id }.toSet()
									val next = (selected ?: all).let { if (bone.id in it) it - bone.id else it + bone.id }
									bake.boneIds = if (next == all) null else next
									viewModel.refreshBakePreview()
								},
								height = 20.dp,
							)
						}
					}
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						LabelText(tr("animation.bake.timeRange"), 60)
						OptionalTime(bake.start, viewModel) { bake.start = it }
						Text("–", color = colors.textMuted, style = typography.caption)
						OptionalTime(bake.end, viewModel) { bake.end = it }
					}
				}

				// --- Sampling and tolerance ----------------------------------------------------
				BakeSection(tr("animation.bake.sampling")) {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						LabelText(tr("animation.bake.fps"), 60)
						CompactNumberSpinner(
							value = bake.fps.toDouble(),
							onValueChange = { bake.fps = it.toFloat().coerceIn(1f, 120f); viewModel.refreshBakePreview() },
							min = 1.0, max = 120.0, step = 1.0, height = 22.dp, modifier = Modifier.width(64.dp),
							onEditStart = { viewModel.beginEditorField(BAKE_FIELD) }, onEditEnd = { viewModel.endEditorField(BAKE_FIELD) },
						)
						Box(Modifier.width(10.dp))
						LabelText(tr("animation.bake.tolerance"), 60)
						CompactNumberSpinner(
							value = bake.tolerance.toDouble(),
							onValueChange = { bake.tolerance = it.toFloat().coerceIn(0f, 100f); viewModel.refreshBakePreview() },
							min = 0.0, max = 100.0, step = 0.05, decimals = 2, height = 22.dp, modifier = Modifier.width(72.dp),
							onEditStart = { viewModel.beginEditorField(BAKE_FIELD) }, onEditEnd = { viewModel.endEditorField(BAKE_FIELD) },
						)
					}
					Text(tr("animation.bake.toleranceTip"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						LabelText(tr("animation.bake.curve"), 60)
						CompactToggleChip(tr("animation.bake.curveLinear"), bake.shape == BakeShape.LINEAR, { bake.shape = BakeShape.LINEAR; viewModel.refreshBakePreview() }, showCheckWhenSelected = false, height = 20.dp)
						CompactToggleChip(tr("animation.bake.curveBezier"), bake.shape == BakeShape.BEZIER, { bake.shape = BakeShape.BEZIER; viewModel.refreshBakePreview() }, showCheckWhenSelected = false, height = 20.dp)
					}
				}

				// --- Output --------------------------------------------------------------------
				BakeSection(tr("animation.bake.output")) {
					val choices = remember(clips) { listOf(ClipChoice(null, tr("animation.bake.newClip"))) + clips.map { ClipChoice(it.id, it.name) } }
					val selected = choices.firstOrNull { it.id == bake.targetClipId } ?: choices.first()
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						LabelText(tr("animation.bake.target"), 60)
						CompactDropdown(
							items = choices, selectedItem = selected,
							onItemSelected = { bake.targetClipId = it.id },
							itemLabel = { it.label }, modifier = Modifier.width(180.dp), height = 22.dp,
						)
						if (bake.targetClipId == null || choices.none { it.id == bake.targetClipId }) {
							CompactTextField(
								value = bake.newName, onValueChange = { bake.newName = it },
								placeholder = tr("animation.bake.defaultName"), modifier = Modifier.width(160.dp), height = 22.dp,
								onEditStart = { viewModel.beginEditorField(BAKE_FIELD) }, onEditEnd = { viewModel.endEditorField(BAKE_FIELD) },
							)
						}
					}
					if (choices.any { it.id != null && it.id == bake.targetClipId }) {
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							LabelText(tr("animation.bake.write"), 60)
							CompactToggleChip(tr("animation.bake.writeReplace"), bake.write == BakeWrite.REPLACE, { bake.write = BakeWrite.REPLACE }, showCheckWhenSelected = false, height = 20.dp)
							CompactToggleChip(tr("animation.bake.writeMerge"), bake.write == BakeWrite.MERGE, { bake.write = BakeWrite.MERGE }, showCheckWhenSelected = false, height = 20.dp)
							Text(
								tr(if (bake.write == BakeWrite.REPLACE) "animation.bake.writeReplaceTip" else "animation.bake.writeMergeTip"),
								style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted,
							)
						}
					}
				}
			}

			// The preview stays in view however far the sections above are scrolled.
			// --- Preview -------------------------------------------------------------------
			BakeSection(tr("animation.bake.preview")) {
				when {
					bake.rows.isEmpty() -> Text(tr("animation.bake.needPose"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
					preview?.error != null -> Text(tr("animation.bake.cannot", preview.error), style = typography.caption.copy(fontSize = 11.sp), color = colors.error)
					result == null || bake.computing -> Text(tr("animation.bake.computing"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
					else -> {
						val fewer = if (result.sampleCount == 0) 0 else (100f * (1f - result.keyCount.toFloat() / result.sampleCount)).roundToInt()
						Text(
							tr("animation.bake.previewStats", result.curves.size, result.sampleCount, result.keyCount, fewer) +
								"  ·  " + tr("animation.bake.previewError", "%.3f".format(result.maxError)),
							style = typography.body.copy(fontSize = 12.sp), color = colors.textPrimary,
						)
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							CompactIconButton(onClick = { bake.previewPlaying = !bake.previewPlaying }, size = 22.dp) {
								if (bake.previewPlaying) IconPause(modifier = Modifier.size(11.dp)) else IconPlay(modifier = Modifier.size(11.dp), tint = colors.accent)
							}
							CompactSlider(
								value = bake.previewTime,
								onValueChange = { bake.previewPlaying = false; viewModel.previewBakeAt(it) },
								valueRange = result.start..maxOf(result.end, result.start + 0.001f),
								modifier = Modifier.weight(1f),
							)
							Text("%.2f / %.2f s".format(bake.previewTime, result.end), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
						}
					}
				}
			}

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
				CompactButton(text = tr("action.cancel"), onClick = viewModel::closeSkeletonBake)
				CompactButton(
					text = tr("animation.bake.confirm"),
					onClick = viewModel::commitSkeletonBake,
					enabled = result != null && !bake.computing,
					isPrimary = true,
				)
			}
		}
	}
}

@Composable
private fun BakeRowItem(index: Int, row: BakePoseRow, ikBones: List<SkeletonBone>, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val modes = remember(ikBones) { listOf(IkChoice(null, tr("animation.bake.ikNone"))) + ikBones.map { IkChoice(it.id, tr("animation.bake.ikTarget") + " " + it.name) } }
	val eases = remember { PoseEase.entries }
	Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text("${index + 1}", style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, modifier = Modifier.width(20.dp))
		CompactNumberSpinner(
			value = row.time.toDouble(),
			onValueChange = { value -> viewModel.updateBakeRow(row.id) { it.copy(time = value.toFloat().coerceIn(0f, 600f)) } },
			min = 0.0, max = 600.0, step = 0.1, decimals = 2, unit = "s", height = 22.dp, modifier = Modifier.width(86.dp),
			onEditStart = { viewModel.beginEditorField(BAKE_FIELD) }, onEditEnd = { viewModel.endEditorField(BAKE_FIELD) },
		)
		CompactDropdown(
			items = modes, selectedItem = modes.firstOrNull { it.boneId == row.ikBoneId } ?: modes.first(),
			onItemSelected = { viewModel.setBakeRowIk(row.id, it.boneId) },
			itemLabel = { it.label }, modifier = Modifier.width(150.dp), height = 22.dp,
		)
		CompactDropdown(
			items = eases, selectedItem = row.ease,
			onItemSelected = { ease -> viewModel.updateBakeRow(row.id) { it.copy(ease = ease) } },
			itemLabel = { tr("animation.bake.ease." + it.name.lowercase()) }, modifier = Modifier.width(96.dp), height = 22.dp,
		)
		if (row.ikBoneId != null) {
			Text("(${row.ikX.roundToInt()}, ${row.ikY.roundToInt()})", style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, maxLines = 1)
		}
		Box(Modifier.weight(1f))
		CompactIconButton(onClick = { viewModel.recaptureBakeRow(row.id) }, tooltip = tr("animation.bake.recapture"), size = 20.dp) {
			IconReset(modifier = Modifier.size(11.dp))
		}
		CompactIconButton(
			onClick = { viewModel.savePosePreset("", row.values) },
			tooltip = tr("animation.bake.saveSnapshot"), size = 20.dp,
		) { IconSnapshot(modifier = Modifier.size(11.dp)) }
		CompactIconButton(onClick = { viewModel.removeBakeRow(row.id) }, tooltip = tr("animation.bake.removeKey"), size = 20.dp) {
			IconTrash(modifier = Modifier.size(11.dp))
		}
	}
}

/** A time bound that follows the poses until it is set: "auto" or a typed time. */
@Composable
private fun OptionalTime(value: Float?, viewModel: PSD2LiveViewModel, onChange: (Float?) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactToggleChip(tr("animation.bake.auto"), value == null, { onChange(if (value == null) 0f else null); viewModel.refreshBakePreview() }, height = 20.dp)
		if (value != null) {
			CompactNumberSpinner(
				value = value.toDouble(),
				onValueChange = { onChange(it.toFloat().coerceIn(0f, 600f)); viewModel.refreshBakePreview() },
				min = 0.0, max = 600.0, step = 0.1, decimals = 2, unit = "s", height = 22.dp, modifier = Modifier.width(86.dp),
				onEditStart = { viewModel.beginEditorField(BAKE_FIELD) }, onEditEnd = { viewModel.endEditorField(BAKE_FIELD) },
			)
		}
	}
}

@Composable
private fun BakeSection(
	title: String,
	onHeaderClick: (() -> Unit)? = null,
	expanded: Boolean = true,
	content: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.panelBackground)
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(6.dp)).padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			Modifier.fillMaxWidth().let { if (onHeaderClick != null) it.clickable(onClick = onHeaderClick) else it },
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			if (onHeaderClick != null) IconChevron(expanded = expanded, modifier = Modifier.size(10.dp))
			Text(title, style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = colors.textMuted)
		}
		content()
	}
}

@Composable
private fun HeaderCell(text: String, widthDp: Int) {
	val colors = LocalToolColors.current
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(widthDp.dp))
}

@Composable
private fun Gap(widthDp: Int) = Box(Modifier.width(widthDp.dp))

@Composable
private fun LabelText(text: String, widthDp: Int) {
	val colors = LocalToolColors.current
	Text(
		text, style = LocalToolTypography.current.caption.copy(fontSize = 11.sp), color = colors.textMuted,
		modifier = Modifier.width(widthDp.dp), maxLines = 1,
	)
}
