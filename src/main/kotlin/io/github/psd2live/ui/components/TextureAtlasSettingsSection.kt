package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

private val atlasOptions = listOf(1024, 2048, 4096, 8192, 16384)

/** Texture atlas settings of the export dialog: upscale, atlas size, padding and the alpha threshold. */
@Composable
internal fun TextureAtlasSettingsSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating
	val minRequiredAtlasSize = state.minRequiredAtlasSize()

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = tr("settings.group.texture"),
				style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
				color = colors.textPrimary,
				modifier = Modifier.weight(1f),
			)
			CompactButton(
				text = tr("settings.reset"),
				onClick = viewModel::resetTextureAtlasToDefault,
				enabled = !isBusy,
				leadingIcon = { IconReset(tint = colors.textPrimary) },
				height = 19.dp,
			)
		}

		AtlasRow(tr("upscale.title")) {
			CompactButton(
				text = if (state.textureUpscale.scale == 1) tr("upscale.off") else "${state.textureUpscale.scale}× (${state.textureUpscale.tileSize}px)",
				isPrimary = state.textureUpscale.scale > 1,
				enabled = !isBusy,
				onClick = { viewModel.openTextureUpscaleDialog() },
				modifier = Modifier.weight(1f),
				height = 22.dp,
			)
		}

		AtlasRow(tr("settings.atlasSize")) {
			CompactDropdown(
				items = atlasOptions,
				selectedItem = state.atlasSize.takeIf { it in atlasOptions } ?: atlasOptions[2],
				onItemSelected = { viewModel.setAtlasSize(it) },
				itemLabel = { size ->
					if (size < minRequiredAtlasSize) "$size × $size (${tr("settings.atlasTooSmall")})" else "$size × $size"
				},
				itemEnabled = { size -> size >= minRequiredAtlasSize },
				modifier = Modifier.weight(1f),
				enabled = !isBusy,
				height = 22.dp,
			)
			Spacer(Modifier.width(4.dp))
			CompactNumberSpinner(
				onEditStart = { viewModel.beginEditorField("setAtlasSize") },
				onEditEnd = { viewModel.endEditorField("setAtlasSize") },
				value = state.atlasSize.toDouble(),
				onValueChange = { viewModel.setAtlasSize(it.toInt()) },
				min = maxOf(256.0, minRequiredAtlasSize.toDouble()),
				max = 16384.0,
				step = 256.0,
				decimals = 0,
				enabled = !isBusy,
				modifier = Modifier.width(70.dp),
				height = 22.dp,
			)
		}

		AtlasRow(tr("settings.texturePadding")) {
			CompactNumberSpinner(
				onEditStart = { viewModel.beginEditorField("setTexturePadding") },
				onEditEnd = { viewModel.endEditorField("setTexturePadding") },
				value = state.texturePadding.toDouble(),
				onValueChange = { viewModel.setTexturePadding(it.toInt()) },
				min = 0.0,
				max = 32.0,
				step = 1.0,
				decimals = 0,
				unit = tr("settings.unit.px"),
				enabled = !isBusy,
				modifier = Modifier.weight(1f),
				height = 22.dp,
			)
			Spacer(Modifier.width(6.dp))
			Text(
				text = tr("settings.alphaThreshold"),
				style = typography.body.copy(fontSize = 11.sp),
				color = colors.textPrimary,
				modifier = Modifier.width(64.dp),
				textAlign = TextAlign.Right,
			)
			Spacer(Modifier.width(4.dp))
			CompactNumberSpinner(
				onEditStart = { viewModel.beginEditorField("setAlphaThreshold") },
				onEditEnd = { viewModel.endEditorField("setAlphaThreshold") },
				value = state.alphaThreshold.toDouble(),
				onValueChange = { viewModel.setAlphaThreshold(it.toInt()) },
				min = 0.0,
				max = 255.0,
				step = 1.0,
				decimals = 0,
				unit = tr("settings.unit.byte"),
				enabled = !isBusy,
				modifier = Modifier.width(70.dp),
				height = 22.dp,
			)
		}
	}
}

@Composable
private fun AtlasRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = typography.body.copy(fontSize = 11.sp),
			color = colors.textPrimary,
			modifier = Modifier.width(80.dp),
			textAlign = TextAlign.Right,
		)
		Spacer(Modifier.width(6.dp))
		content()
	}
}
