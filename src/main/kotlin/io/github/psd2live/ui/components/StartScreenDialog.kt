package io.github.psd2live.ui.components

import io.github.psd2live.ui.utils.toImageBitmapFast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.HairMode
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PresetParts
import io.github.psd2live.ui.state.StartPresetChoices
import io.github.psd2live.ui.state.StartQuickPreset
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.roundToInt

internal enum class SplitNamesMode { LR, TB, NUMBER, CUSTOM }

internal class BatchSplitItemState(
    val offer: PSD2LiveViewModel.MeshSplitOffer,
    initialSelected: Boolean = true,
) {
    var isSelected by mutableStateOf(initialSelected)
    val components = offer.plan.components
    val count = components.size
    val horizontal = count == 2 &&
        kotlin.math.abs(components[0].centerX - components[1].centerX) >=
        kotlin.math.abs(components[0].centerY - components[1].centerY)
    var mode by mutableStateOf(if (count == 2) { if (horizontal) SplitNamesMode.LR else SplitNamesMode.TB } else SplitNamesMode.NUMBER)
    var customNames by mutableStateOf(List(count) { "${offer.layerName}-${it + 1}" })

    fun generatedNamesAndSides(): Pair<List<String>, List<Side>> {
        val order = when (mode) {
            SplitNamesMode.LR -> components.indices.sortedBy { components[it].centerX }
            SplitNamesMode.TB -> components.indices.sortedBy { components[it].centerY }
            else -> components.indices.toList()
        }
        val suffixes = when (mode) {
            SplitNamesMode.LR -> listOf("r", "l")
            SplitNamesMode.TB -> listOf("t", "b")
            else -> (1..count).map(Int::toString)
        }
        val generated = MutableList(count) { "" }
        val sides = MutableList(count) { Side.NONE }
        order.forEachIndexed { position, component ->
            generated[component] = if (mode == SplitNamesMode.CUSTOM) customNames[component].trim()
                else "${offer.layerName}-${suffixes[position]}"
            if (mode == SplitNamesMode.LR) sides[component] = if (position == 0) Side.RIGHT else Side.LEFT
        }
        return generated to sides
    }

    val isValid: Boolean
        get() {
            if (!isSelected) return true
            val (names, _) = generatedNamesAndSides()
            return names.all { it.isNotBlank() } && names.distinct().size == count
        }
}

/**
 * The start screen after a PSD import, also under Tools: quick preset sets, the model presets they fill in,
 * and the layers whose mesh falls apart into pieces, each to be split or kept. Without presets in the
 * [offer] it only lists the splits. [onApply] gets the preset choices (null without presets) and the splits.
 */
@Composable
internal fun StartScreenDialog(
    offer: PSD2LiveViewModel.StartScreenOffer,
    onApply: (StartPresetChoices?, List<PSD2LiveViewModel.LayerSplitDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val splits = offer.splits
    val itemStates = remember(splits) { splits.orEmpty().map { BatchSplitItemState(it) } }
    val previewsByOffer = remember(splits) {
        splits.orEmpty().associate { split -> split.layerId to split.plan.previewImages.map { it.toImageBitmapFast() } }
    }
    var choices by remember(offer.initial) { mutableStateOf(offer.initial) }
    var showOnImport by remember { mutableStateOf(AppSettings.autoDetectMeshSplitsOnImport) }
    val parts = remember(offer.preview) { PresetParts.of(offer.preview.analysis) }

    val selectedItems = itemStates.filter { it.isSelected }
    val allValid = selectedItems.all { it.isValid }
    val canApply = allValid && (offer.presets || selectedItems.isNotEmpty())

    fun decisions() = selectedItems.map { item ->
        val (names, sides) = item.generatedNamesAndSides()
        PSD2LiveViewModel.LayerSplitDecision(item.offer, names, sides)
    }

    Box(
        Modifier.fillMaxSize().background(colors.scrim).scrimDismiss(onDismiss = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(if (offer.presets) 900.dp else 720.dp)
                    .then(if (offer.presets) Modifier.height(minOf(maxHeight, 640.dp)) else Modifier.heightIn(max = minOf(maxHeight, 620.dp)))
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panelElevated)
                    .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
                    .clickable(enabled = false) {},
            ) {
                StartHeader(offer)
                Divider(color = colors.divider, thickness = 1.dp)

                Row(Modifier.fillMaxWidth().weight(1f, fill = offer.presets)) {
                    if (offer.presets) {
                        Column(
                            Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QuickPresetRow(choices, parts) { choices = it.choices }
                            Spacer(Modifier.height(4.dp))
                            PresetChoicesPanel(choices, parts) { choices = it }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                    }
                    Column(
                        Modifier.weight(1f).then(if (offer.presets) Modifier.fillMaxHeight() else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SplitHeader(itemStates, scanning = splits == null)
                        when {
                            splits == null -> EmptyNote(tr("start.splits.scanning"))
                            itemStates.isEmpty() -> EmptyNote(tr("start.splits.none"))
                            else -> Column(
                                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemStates.forEach { SplitCard(it, previewsByOffer[it.offer.layerId].orEmpty()) }
                            }
                        }
                    }
                }

                Divider(color = colors.divider, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactCheckbox(
                        checked = showOnImport,
                        onCheckedChange = {
                            showOnImport = it
                            AppSettings.autoDetectMeshSplitsOnImport = it
                        },
                        label = tr("editor.meshSplit.promptOnImport"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(text = tr("start.skip"), onClick = onDismiss)
                        CompactButton(
                            text = if (offer.presets) tr("start.apply")
                                else tr("editor.meshSplit.confirmSelected", selectedItems.size),
                            onClick = { onApply(if (offer.presets) choices else null, decisions()) },
                            enabled = canApply,
                            isPrimary = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartHeader(offer: PSD2LiveViewModel.StartScreenOffer) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val source = offer.preview.analysis.source
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (offer.presets) tr("start.title") else tr("editor.meshSplit.batchTitle"),
                style = typography.title.copy(fontSize = 16.sp),
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                tr("start.subtitle", source.widthPx.toString(), source.heightPx.toString(), offer.preview.analysis.layers.size),
                style = typography.caption,
                color = colors.textMuted,
            )
        }
        Text(
            if (offer.presets) tr("start.body") else tr("editor.meshSplit.batchBody", offer.splits.orEmpty().size),
            style = typography.body.copy(fontSize = 12.sp),
            color = colors.textMuted,
        )
    }
}

/** A small dropdown of the quick sets, on the one matching the current choices, or on "Custom" when none does. */
@Composable
private fun QuickPresetRow(choices: StartPresetChoices, parts: PresetParts, onPick: (StartQuickPreset) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val matched = StartQuickPreset.entries.firstOrNull { it.choices.within(parts) == choices.within(parts) }
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("start.quick"), style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary, modifier = Modifier.width(80.dp))
        CompactDropdown(
            items = if (matched == null) StartQuickPreset.entries + null else StartQuickPreset.entries,
            selectedItem = matched,
            onItemSelected = { it?.let(onPick) },
            itemLabel = { it?.let { preset -> tr(preset.key) } ?: tr("start.quick.custom") },
            itemEnabled = { it != null },
            height = 20.dp,
            modifier = Modifier.width(110.dp),
        )
    }
}

/** The model presets the start screen sets, grouped as in the model presets panel. */
@Composable
private fun PresetChoicesPanel(choices: StartPresetChoices, parts: PresetParts, onChange: (StartPresetChoices) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    SectionLabel(tr("settings.group.rigging"))
    StrengthRow(tr("settings.headStrength"), choices.headStrength) { onChange(choices.copy(headStrength = it)) }
    StrengthRow(tr("settings.bodyStrength"), choices.bodyStrength) { onChange(choices.copy(bodyStrength = it)) }
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactCheckbox(choices.featureDisplacement, { onChange(choices.copy(featureDisplacement = it)) },
            label = tr("model.deformer.featureDisplacement"), modifier = Modifier.weight(1f))
        CompactCheckbox(choices.mouthOutline, { onChange(choices.copy(mouthOutline = it)) },
            label = tr("mouth.outline"), modifier = Modifier.weight(1f))
    }

    Spacer(Modifier.height(6.dp))
    SectionLabel(tr("settings.group.motions"))
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactCheckbox(choices.motionBasic, { onChange(choices.copy(motionBasic = it)) },
            label = tr("settings.motion.basic"), modifier = Modifier.weight(1f))
        CompactCheckbox(choices.motionSkeleton, { onChange(choices.copy(motionSkeleton = it)) },
            label = tr("settings.motion.skeleton"), modifier = Modifier.weight(1f))
    }

    Spacer(Modifier.height(6.dp))
    SectionLabel(tr("settings.group.simulation"))
    for (front in listOf(true, false)) {
        val exists = if (front) parts.frontHair else parts.backHair
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(if (front) "presets.frontHair" else "presets.backHair"),
                style = typography.body.copy(fontSize = 11.5.sp),
                color = if (exists) colors.textPrimary else colors.textDisabled,
                modifier = Modifier.width(52.dp),
            )
            CompactDropdown(
                items = HairMode.entries,
                selectedItem = if (front) choices.frontHair else choices.backHair,
                onItemSelected = { onChange(if (front) choices.copy(frontHair = it) else choices.copy(backHair = it)) },
                itemLabel = { tr(it.key) },
                enabled = exists,
                height = 20.dp,
                modifier = Modifier.width(110.dp),
            )
            if (!exists) Note(tr("presets.status.absent"))
        }
    }
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactCheckbox(choices.clothing && parts.clothing, { onChange(choices.copy(clothing = it)) },
            label = tr("presets.clothing"), enabled = parts.clothing)
        if (!parts.clothing) Note(tr("presets.status.noClothing"))
    }
    if (parts.clothing) Text(
        tr("presets.clothingHint"),
        style = typography.caption.copy(fontSize = 10.5.sp),
        color = colors.textMuted,
        modifier = Modifier.padding(start = 22.dp),
    )
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactCheckbox(choices.eyeJelly && parts.eyeJelly, { onChange(choices.copy(eyeJelly = it)) },
            label = tr("export.physics.eyeJelly"), enabled = parts.eyeJelly)
        if (!parts.eyeJelly) Note(tr("presets.status.absent"))
    }

    Spacer(Modifier.height(6.dp))
    Text(tr("start.presets.note"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
}

@Composable
private fun StrengthRow(label: String, value: Float, onChange: (Float) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = typography.body.copy(fontSize = 11.5.sp), color = colors.textPrimary, modifier = Modifier.width(80.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompactSlider(
            value = value,
            onValueChange = { onChange((it * 20f).roundToInt() / 20f) },
            valueRange = 0f..4f,
            height = 14.dp,
            modifier = Modifier.weight(1f),
        )
        Text("%.2f×".format(value), style = typography.monoSmall, color = colors.textPrimary,
            modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

@Composable
private fun SplitHeader(items: List<BatchSplitItemState>, scanning: Boolean) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(tr("start.splits"))
        if (!scanning && items.isNotEmpty()) Text(
            tr("editor.meshSplit.selectedCount", items.count { it.isSelected }, items.size),
            style = typography.caption,
            color = colors.textMuted,
        )
        Spacer(Modifier.weight(1f))
        if (!scanning && items.isNotEmpty()) {
            CompactButton(tr("editor.meshSplit.selectAll"), { items.forEach { it.isSelected = true } }, height = 22.dp)
            CompactButton(tr("editor.meshSplit.deselectAll"), { items.forEach { it.isSelected = false } }, height = 22.dp)
        }
    }
}

/** A layer that can be split: its check, name, how the pieces are named, and each piece with its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitCard(item: BatchSplitItemState, previews: List<androidx.compose.ui.graphics.ImageBitmap>) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val count = item.count
    val (generatedNames, _) = item.generatedNamesAndSides()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (item.isSelected) colors.panelBackground else colors.inputBackground.copy(alpha = 0.5f))
            .border(
                BorderStroke(1.dp, if (item.isSelected) colors.accent.copy(alpha = 0.5f) else colors.divider),
                RoundedCornerShape(6.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactCheckbox(checked = item.isSelected, onCheckedChange = { item.isSelected = it })
            Text(
                item.offer.layerName,
                style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (item.isSelected) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(tr("editor.meshSplit.componentsCount", count), style = typography.caption, color = colors.textMuted)
            Spacer(Modifier.weight(1f))
            if (item.isSelected) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val choices = if (count == 2) listOf(
                        SplitNamesMode.LR to "L / R",
                        SplitNamesMode.TB to "T / B",
                        SplitNamesMode.NUMBER to "1 / 2",
                        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
                    ) else listOf(
                        SplitNamesMode.NUMBER to "1…$count",
                        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
                    )
                    choices.forEach { (choice, label) ->
                        CompactToggleChip(
                            text = label,
                            selected = item.mode == choice,
                            onToggle = { item.mode = choice },
                            showCheckWhenSelected = false,
                        )
                    }
                }
            }
        }

        if (item.isSelected) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.components.indices.forEach { index ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(4.dp))
                                .background(colors.inputBackground)
                                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (index < previews.size) {
                                Image(
                                    bitmap = previews[index],
                                    contentDescription = tr("editor.meshSplit.preview", index + 1),
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        if (item.mode == SplitNamesMode.CUSTOM) {
                            CompactTextField(
                                value = item.customNames.getOrElse(index) { "" },
                                onValueChange = { value ->
                                    item.customNames = item.customNames.toMutableList().also {
                                        if (index < it.size) it[index] = value
                                    }
                                },
                                modifier = Modifier.width(100.dp),
                            )
                        } else {
                            Text(
                                generatedNames.getOrElse(index) { "" },
                                style = typography.caption.copy(fontSize = 11.sp),
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalToolColors.current
    Text(
        text,
        style = LocalToolTypography.current.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.Note(text: String) {
    Text(
        text,
        style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
        color = LocalToolColors.current.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = 8.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 11.5.sp), color = LocalToolColors.current.textMuted)
    }
}
