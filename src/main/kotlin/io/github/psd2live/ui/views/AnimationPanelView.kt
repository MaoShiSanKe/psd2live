package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewControlCanvas
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

private const val MOTION_SETTINGS_FIELD = "motion-settings"
private const val BUILTIN_SECTION = "builtin"
private const val CUSTOM_SECTION = "custom"
private val MotionRowHeight = 24.dp
private val MotionRowIndent = 12.dp

/** One motion as the panel lists it, generated or the user's own. */
private data class MotionEntry(
	val key: String,
	val title: String,
	val playName: String,
	val clipId: String?,
	val summary: MotionSummary,
	val enabled: Boolean,
	val onEnabledChange: (Boolean) -> Unit,
	val modified: Boolean,
	val onEdit: () -> Unit,
	val onEditProperties: ((MotionClip) -> MotionClip) -> Unit,
	val onFocusParameter: (String) -> Unit,
	val onRename: ((String) -> Unit)?,
	val menu: @Composable ((() -> Unit)) -> Unit,
)

/** What a motion row shows: its timing and each curve's range. */
private data class MotionSummary(
	val loop: Boolean,
	val duration: Float,
	val fps: Float,
	val fadeIn: Float,
	val fadeOut: Float,
	val curves: List<Pair<String, Pair<Float, Float>>>,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AnimationPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val previewState = state.previewPanelState()
	val skeleton = state.rigEdits.skeleton
	val isPlaying = previewState.animationEnabled && !previewState.meshOnly
	var searchOpen by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val searchFocus = remember { FocusRequester() }
	fun closeSearch() {
		query = ""
		searchOpen = false
	}
	val openSections = remember { mutableStateMapOf(BUILTIN_SECTION to true, CUSTOM_SECTION to true) }
	val openSettings = remember { mutableStateMapOf<String, Boolean>() }
	var newMenuOpen by remember { mutableStateOf(false) }

	val builtins = builtinEntries(viewModel, previewState)
	val customs = customEntries(viewModel, previewState)
	val needle = query.trim()
	fun List<MotionEntry>.matching() = if (needle.isEmpty()) this else filter { it.title.contains(needle, ignoreCase = true) }
	val shownBuiltins = builtins.matching()
	val shownCustoms = customs.matching()
	val parameterNames = remember(state.previewModel) {
		state.previewModel?.rig?.puppet?.parameters.orEmpty().associate { it.id.raw to it.name }
	}

	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		BoxWithConstraints(
			Modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.padding(horizontal = 4.dp, vertical = 3.dp)
				.height(22.dp),
		) {
			// Labels appear in this order as the panel widens, each only once everything before it fits.
			val labels = listOf(
				tr(if (isPlaying) "animation.idle.stop" else "animation.idle.start"),
				tr("animation.mouseTracking"),
				tr("animation.new"),
			)
			val labelsShown = shownToolLabels(labels, if (state.previewLive) 7 else 8, maxWidth)
			Row(
				modifier = Modifier.fillMaxSize(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(3.dp),
			) {
				if (searchOpen) {
					LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
					CompactTextField(
						value = query,
						onValueChange = { query = it },
						placeholder = tr("animation.search"),
						leadingIcon = { IconSearch(tint = colors.textMuted) },
						trailingIcon = {
							CompactIconButton(
								onClick = { closeSearch() },
								tooltip = tr("parameters.clearSearch"), size = 16.dp,
							) { IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted) }
						},
						modifier = Modifier
							.weight(1f)
							.focusRequester(searchFocus)
							.onPreviewKeyEvent { event ->
								if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
									closeSearch()
									true
								} else false
							},
						height = 22.dp,
					)
				} else {
					CompactIconButton(onClick = { searchOpen = true }, size = 22.dp, tooltip = tr("animation.search")) {
						IconSearch(tint = colors.textMuted)
					}
					PanelToolButton(
						label = labels[0],
						showLabel = labelsShown > 0,
						onClick = { viewModel.setAnimationEnabled(!previewState.animationEnabled) },
						enabled = true,
						active = isPlaying,
						tooltip = labels[0],
					) {
						if (isPlaying) IconPause(modifier = Modifier.size(11.dp), tint = colors.accent)
						else IconPlay(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
					}
					PanelToolButton(
						label = labels[1],
						showLabel = labelsShown > 1,
						onClick = { viewModel.setMouseTrackingEnabled(!previewState.mouseTrackingEnabled) },
						enabled = true,
						active = previewState.mouseTrackingEnabled,
						tooltip = labels[1],
					) {
						IconMouse(
							active = previewState.mouseTrackingEnabled,
							modifier = Modifier.size(12.dp),
							tint = if (previewState.mouseTrackingEnabled) colors.accent else colors.textMuted,
						)
					}
					PanelToolbarSeparator()
					Box {
						PanelToolButton(
							label = labels[2],
							showLabel = labelsShown > 2,
							onClick = { newMenuOpen = true },
							enabled = state.previewModel != null,
							tooltip = tr("animation.new"),
						) {
							IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
						}
						NewMotionMenu(viewModel, skeleton, newMenuOpen) { newMenuOpen = false }
					}
					Spacer(Modifier.weight(1f))
					if (!state.previewLive) {
						CompactIconButton(
							onClick = { viewModel.ensurePreviewCanvas(focus = true) },
							size = 22.dp,
							tooltip = tr("window.showPreview"),
						) {
							IconEye(visible = true, modifier = Modifier.size(12.dp), tint = colors.textMuted)
						}
					}
					CompactIconButton(
						onClick = { openSections.keys.toList().forEach { openSections[it] = true } },
						size = 22.dp,
						tooltip = tr("canvas.hierarchy.expandAll"),
					) {
						IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
					}
					CompactIconButton(
						onClick = {
							openSections.keys.toList().forEach { openSections[it] = false }
							openSettings.clear()
						},
						size = 22.dp,
						tooltip = tr("canvas.hierarchy.collapseAll"),
					) {
						IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
					}
					CompactIconButton(
						onClick = { viewModel.resetPreviewParameters() },
						enabled = state.previewModel != null,
						size = 22.dp,
						tooltip = tr("animation.resetPose"),
					) {
						IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
					}
				}
			}
		}
		Divider(color = colors.divider)

		if (state.previewModel == null || (needle.isNotEmpty() && shownBuiltins.isEmpty() && shownCustoms.isEmpty())) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = tr(if (state.previewModel == null) "animation.editor.noModel" else "animation.noResults"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(12.dp),
				)
			}
			return@Column
		}

		val builtinTitle = if (state.activeWorkspace.canvases.size > 1)
			"${viewModel.canvasTitle(state.previewControlCanvas())} · ${tr("animation.builtinSection")}"
		else tr("animation.builtinSection")
		val listState = rememberLazyListState()
		Box(Modifier.fillMaxSize()) {
			LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
				fun LazyListScope.section(id: String, title: String, entries: List<MotionEntry>, empty: String?) {
					val open = needle.isNotEmpty() || openSections[id] != false
					item(key = "s:$id") {
						PanelSectionRow(title, open, { openSections[id] = !open }, count = entries.size)
						Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
					}
					if (!open) return
					items(entries, key = { "m:${it.key}" }) { entry ->
						MotionRow(
							viewModel = viewModel,
							state = previewState,
							entry = entry,
							parameterNames = parameterNames,
							settingsOpen = openSettings[entry.key] == true,
							onToggleSettings = { openSettings[entry.key] = openSettings[entry.key] != true },
						)
						Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
					}
					if (entries.isEmpty() && empty != null) {
						item(key = "e:$id") {
							Text(
								text = empty,
								style = typography.caption.copy(fontSize = 10.5.sp),
								color = colors.textMuted,
								modifier = Modifier.fillMaxWidth().padding(start = MotionRowIndent + 4.dp, top = 6.dp, bottom = 6.dp),
							)
						}
					}
				}
				section(BUILTIN_SECTION, builtinTitle, shownBuiltins, null)
				section(CUSTOM_SECTION, tr("animation.customSection"), shownCustoms, tr("animation.customEmpty").takeIf { needle.isEmpty() })
			}
			VerticalScrollbar(
				adapter = rememberScrollbarAdapter(listState),
				modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
			)
		}
	}
}

/** Blank clip or a copy of a generated motion, opened from the toolbar. */
@Composable
private fun NewMotionMenu(viewModel: PSD2LiveViewModel, skeleton: SkeletonSpec?, open: Boolean, onDismiss: () -> Unit) {
	TreeContextMenu(expanded = open, onDismissRequest = onDismiss) {
		CompactMenuItem(text = tr("animation.newBlank"), onClick = { onDismiss(); viewModel.createMotionClip() })
		CompactMenuDivider()
		CompactMenuSection(tr("animation.newFromPreset"))
		for (name in MotionClips.BUILTIN_NAMES) {
			if (MotionClips.builtinTracks(name, skeleton).isEmpty()) continue
			CompactMenuItem(text = builtinMotionTitle(name), onClick = { onDismiss(); viewModel.createMotionClip(fromBuiltin = name) })
		}
	}
}

/** True when [override] still matches what [name] generates, so it reads as unmodified. */
private fun isPristine(override: MotionClip, name: String, skeleton: SkeletonSpec?): Boolean {
	val tracks = MotionClips.builtinTracks(name, skeleton)
	val fresh = MotionClips.fromTracks(override.id, override.name, override.builtin, MotionClips.isLoopBuiltin(name), tracks,
		MotionClips.builtinDuration(name, tracks))
	return fresh.copy(enabled = override.enabled) == override
}

private fun builtinEntries(viewModel: PSD2LiveViewModel, state: PSD2LiveState): List<MotionEntry> {
	val clips = state.rigEdits.motionClips
	val skeleton = state.rigEdits.skeleton
	return MotionClips.BUILTIN_NAMES.mapNotNull { name ->
		val override = MotionClips.overrideOf(clips, name)
		val tracks = MotionClips.builtinTracks(name, skeleton)
		// Skeleton presets only list when the current skeleton can play them.
		if (override == null && tracks.isEmpty()) return@mapNotNull null
		val (enabled, setEnabled) = when (name) {
			"Idle" -> state.motionIdle to viewModel::setMotionIdle
			"Blink" -> state.motionBlink to viewModel::setMotionBlink
			"Nod" -> state.motionNod to viewModel::setMotionNod
			"Shake" -> state.motionShake to viewModel::setMotionShake
			else -> state.motionSkeleton to viewModel::setMotionSkeleton
		}
		MotionEntry(
			key = "builtin:$name",
			title = builtinMotionTitle(name),
			playName = name,
			clipId = override?.id,
			summary = override?.let(::summaryOf) ?: MotionSummary(
				loop = MotionClips.isLoopBuiltin(name),
				duration = MotionClips.builtinDuration(name, tracks),
				fps = 30f,
				fadeIn = 1f,
				fadeOut = 1f,
				curves = tracks.map { (id, points) -> id to (points.minOf { it.second } to points.maxOf { it.second }) },
			),
			enabled = enabled,
			onEnabledChange = setEnabled,
			modified = override != null && !isPristine(override, name, skeleton),
			onEdit = { viewModel.editBuiltinMotion(name) },
			onEditProperties = { transform -> viewModel.updateMotionClipProperties(viewModel.ensureBuiltinOverride(name), transform) },
			onFocusParameter = { id ->
				viewModel.editBuiltinMotion(name)
				viewModel.focusMotionCurve(id)
			},
			onRename = null,
			menu = { dismiss ->
				CompactMenuItem(text = tr("animation.duplicateAsCustom"), onClick = { dismiss(); viewModel.createMotionClip(fromBuiltin = name) })
				CompactMenuItem(
					text = tr("animation.resetDefault"),
					enabled = override != null,
					onClick = { dismiss(); viewModel.resetBuiltinMotion(name) },
				)
			},
		)
	}
}

private fun customEntries(viewModel: PSD2LiveViewModel, state: PSD2LiveState): List<MotionEntry> {
	val stems = MotionClips.exportStems(state.rigEdits.motionClips)
	return state.rigEdits.motionClips.filter { it.builtin == null }.map { clip ->
		MotionEntry(
			key = "clip:${clip.id}",
			title = clip.name,
			playName = stems.getValue(clip.id),
			clipId = clip.id,
			summary = summaryOf(clip),
			enabled = clip.enabled,
			onEnabledChange = { value -> viewModel.updateMotionClipProperties(clip.id) { it.copy(enabled = value) } },
			modified = false,
			onEdit = { viewModel.openMotionInEditor(clip.id) },
			onEditProperties = { transform -> viewModel.updateMotionClipProperties(clip.id, transform) },
			onFocusParameter = { id ->
				viewModel.openMotionInEditor(clip.id)
				viewModel.focusMotionCurve(id)
			},
			onRename = { viewModel.renameMotionClip(clip.id, it) },
			menu = { dismiss ->
				CompactMenuItem(text = tr("animation.duplicate"), onClick = { dismiss(); viewModel.duplicateMotionClip(clip.id) })
				CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deleteMotionClip(clip.id) })
			},
		)
	}
}

private fun summaryOf(clip: MotionClip) = MotionSummary(
	loop = clip.loop,
	duration = clip.duration,
	fps = clip.fps,
	fadeIn = clip.fadeIn,
	fadeOut = clip.fadeOut,
	curves = clip.curves.map { curve -> curve.parameterId to (curve.keys.minOf { it.value } to curve.keys.maxOf { it.value }) },
)

/**
 * One motion: enable, play and open in the editor on the row, its settings folded underneath and the rest in
 * the right-click menu. The motion open in the editor is marked like a related parameter.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MotionRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	entry: MotionEntry,
	parameterNames: Map<String, String>,
	settingsOpen: Boolean,
	onToggleSettings: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	var renaming by remember { mutableStateOf(false) }
	var draftName by remember(entry.title) { mutableStateOf(entry.title) }
	val renameFocus = remember { FocusRequester() }
	val activeMotionName = viewModel.activeMotionName
	val playing = entry.playName.equals(activeMotionName, ignoreCase = true) ||
		(entry.playName.equals("Idle", ignoreCase = true) && state.animationEnabled && entry.enabled && activeMotionName == null)
	val editing = entry.clipId != null && viewModel.motionEditor.clipId == entry.clipId
	fun commitRename() {
		if (!renaming) return
		renaming = false
		val trimmed = draftName.trim()
		if (trimmed.isNotEmpty() && trimmed != entry.title) entry.onRename?.invoke(trimmed)
	}
	LaunchedEffect(renaming) {
		if (renaming) runCatching { renameFocus.requestFocus() }
	}

	Column(Modifier.fillMaxWidth()) {
		Box {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(MotionRowHeight)
					.background(
						when {
							editing -> colors.selection.copy(alpha = 0.35f)
							hovered -> colors.controlHover.copy(alpha = 0.35f)
							else -> Color.Transparent
						},
					)
					.drawWithContent {
						drawContent()
						if (editing) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
					}
					.hoverable(interaction)
					.onPointerEvent(PointerEventType.Press) { event ->
						if (event.button == PointerButton.Secondary) {
							menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
							menuOpen = true
							event.changes.firstOrNull()?.consume()
						}
					}
					.clickable(interactionSource = interaction, indication = null, enabled = !renaming, onClick = onToggleSettings)
					.padding(start = 4.dp, end = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(expanded = settingsOpen, tint = colors.textMuted, modifier = Modifier.size(10.dp))
				Spacer(Modifier.width(2.dp))
				CompactCheckbox(checked = entry.enabled, onCheckedChange = entry.onEnabledChange)
				Spacer(Modifier.width(4.dp))
				if (renaming) {
					CompactTextField(
						value = draftName,
						onValueChange = { draftName = it },
						modifier = Modifier
							.weight(1f)
							.focusRequester(renameFocus)
							.onPreviewKeyEvent { event ->
								if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
									renaming = false
									true
								} else false
							},
						height = 20.dp,
						selectAllOnFocus = true,
						onCommit = { commitRename() },
						onFocusLost = { commitRename() },
					)
				} else {
					// Name and badges take the free width, so the timing and the buttons sit flush right.
					Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = entry.title,
							style = typography.body.copy(fontSize = 11.sp, fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal),
							color = when {
								!entry.enabled -> colors.textMuted
								editing -> colors.accent
								else -> colors.textPrimary
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f, fill = false),
						)
						if (entry.modified) {
							Spacer(Modifier.width(4.dp))
							MotionBadge(tr("animation.modified"), colors.warning)
						}
						if (playing) {
							Spacer(Modifier.width(4.dp))
							MotionBadge(tr(if (entry.summary.loop) "animation.looping" else "animation.playing"), colors.accent)
						}
					}
				}
				Spacer(Modifier.width(6.dp))
				Text(
					text = "${tr(if (entry.summary.loop) "animation.loop" else "animation.once")} · %.1fs".format(entry.summary.duration),
					style = typography.monoSmall.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
					maxLines = 1,
				)
				Spacer(Modifier.width(6.dp))
				CompactIconButton(onClick = entry.onEdit, size = 18.dp, tooltip = tr("animation.edit")) {
					IconMotionCurve(tint = if (editing) colors.accent else colors.textMuted)
				}
				Spacer(Modifier.width(4.dp))
				CompactIconButton(onClick = { viewModel.triggerMotion(entry.playName) }, size = 18.dp, tooltip = tr("animation.trigger")) {
					IconPlay(modifier = Modifier.size(9.dp), tint = colors.accent)
				}
			}
			TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
				CompactMenuItem(text = tr("animation.edit"), onClick = { menuOpen = false; entry.onEdit() })
				CompactMenuItem(text = tr("animation.trigger"), onClick = { menuOpen = false; viewModel.triggerMotion(entry.playName) })
				if (entry.onRename != null) {
					CompactMenuItem(text = tr("animation.rename"), onClick = { menuOpen = false; draftName = entry.title; renaming = true })
				}
				CompactMenuDivider()
				entry.menu { menuOpen = false }
			}
		}

		AnimatedVisibility(
			visible = settingsOpen,
			enter = expandVertically() + fadeIn(),
			exit = shrinkVertically() + fadeOut(),
		) {
			MotionSettings(viewModel, entry, parameterNames)
		}
	}
}

/** Timing and the parameters a motion drives, indented under its row. */
@Composable
private fun MotionSettings(viewModel: PSD2LiveViewModel, entry: MotionEntry, names: Map<String, String>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val summary = entry.summary
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated.copy(alpha = 0.35f))
			.padding(start = MotionRowIndent + 4.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			SettingField(tr("animation.duration"), summary.duration, 0.1f, 600f, 0.1, 2, Modifier.weight(1f), viewModel) { value ->
				entry.onEditProperties { it.copy(duration = value) }
			}
			SettingField("FPS", summary.fps, 1f, 120f, 1.0, 0, Modifier.weight(1f), viewModel) { value ->
				entry.onEditProperties { it.copy(fps = value) }
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			SettingField(tr("animation.fadeInTime"), summary.fadeIn, 0f, 5f, 0.1, 1, Modifier.weight(1f), viewModel) { value ->
				entry.onEditProperties { it.copy(fadeIn = value) }
			}
			SettingField(tr("animation.fadeOutTime"), summary.fadeOut, 0f, 5f, 0.1, 1, Modifier.weight(1f), viewModel) { value ->
				entry.onEditProperties { it.copy(fadeOut = value) }
			}
		}
		CompactCheckbox(
			checked = summary.loop,
			onCheckedChange = { value -> entry.onEditProperties { it.copy(loop = value) } },
			label = tr("animation.loopPlayback"),
		)
		if (summary.curves.isNotEmpty()) {
			Text(
				text = tr("animation.affectedParams"),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.padding(top = 2.dp),
			)
			summary.curves.forEachIndexed { index, (paramId, range) ->
				AffectedParameterRow(
					color = motionCurveColor(index),
					name = names[paramId]?.ifBlank { null } ?: paramId,
					known = paramId in names,
					range = range,
					onClick = { entry.onFocusParameter(paramId) },
				)
			}
		}
	}
}

/** A driven parameter, tinted like its track in the animation editor; click to open that curve. */
@Composable
private fun AffectedParameterRow(color: Color, name: String, known: Boolean, range: Pair<Float, Float>, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(20.dp)
			.clip(RoundedCornerShape(2.dp))
			.background(if (hovered) colors.controlHover.copy(alpha = 0.55f) else Color.Transparent)
			.hoverable(interaction)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.clickable(interactionSource = interaction, indication = null, onClick = onClick)
			.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Box(Modifier.size(7.dp).clip(CircleShape).background(color))
		Text(
			text = name,
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = if (known) colors.textPrimary else colors.warning,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = "%.1f ~ %.1f".format(range.first, range.second),
			style = typography.monoSmall.copy(fontSize = 9.5.sp),
			color = colors.textMuted,
			maxLines = 1,
		)
	}
}

@Composable
private fun SettingField(
	label: String,
	value: Float,
	min: Float,
	max: Float,
	step: Double,
	decimals: Int,
	modifier: Modifier,
	viewModel: PSD2LiveViewModel,
	onChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		modifier = modifier,
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		CompactNumberSpinner(
			value = value.toDouble(),
			onValueChange = { onChange(it.toFloat().coerceIn(min, max)) },
			min = min.toDouble(),
			max = max.toDouble(),
			step = step,
			decimals = decimals,
			height = 20.dp,
			modifier = Modifier.width(60.dp),
			onEditStart = { viewModel.beginEditorField(MOTION_SETTINGS_FIELD) },
			onEditEnd = { viewModel.endEditorField(MOTION_SETTINGS_FIELD) },
		)
	}
}

@Composable
private fun MotionBadge(label: String, tint: Color) {
	val typography = LocalToolTypography.current
	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(2.dp))
			.background(tint.copy(alpha = 0.16f))
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
			color = tint,
			maxLines = 1,
		)
	}
}

/** An eased curve between two keys: opens the motion in the animation editor. */
@Composable
private fun IconMotionCurve(tint: Color) {
	Canvas(Modifier.size(11.dp)) {
		val w = size.width
		val h = size.height
		val curve = Path().apply {
			moveTo(w * 0.1f, h * 0.85f)
			cubicTo(w * 0.55f, h * 0.85f, w * 0.45f, h * 0.15f, w * 0.9f, h * 0.15f)
		}
		drawPath(curve, tint, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
		val r = 1.6.dp.toPx()
		drawCircle(tint, r, Offset(w * 0.1f, h * 0.85f))
		drawCircle(tint, r, Offset(w * 0.9f, h * 0.15f))
	}
}
