package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DownloadState
import io.github.psd2live.core.ModelDownloader
import io.github.psd2live.core.TextureUpscaleConfig
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Consistent dialog matching PSD2Live compact dark IDE design system. */
@Composable
fun TextureUpscaleDialog(
	config: TextureUpscaleConfig,
	isBusy: Boolean,
	isUpscaling: Boolean = false,
	onDownloadStateChange: (DownloadState) -> Unit,
	onDismiss: () -> Unit,
	onApply: (TextureUpscaleConfig) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var draft by remember(config) { mutableStateOf(config) }
	val localRuntime = remember { TextureUpscaleConfig.localRuntime() }
	val scrollState = rememberScrollState()
	val hasOnly2x = remember(draft.modelDirectory) {
		val p = runCatching { java.nio.file.Path.of(draft.modelDirectory) }.getOrNull()
		p != null && java.nio.file.Files.isRegularFile(p.resolve("scale2x.pth")) && !java.nio.file.Files.isRegularFile(p.resolve("scale4x.pth"))
	}

	var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
	val cancelFlag = remember { AtomicBoolean(false) }
	val coroutineScope = rememberCoroutineScope()
	DisposableEffect(Unit) {
		onDispose {
			cancelFlag.set(true)
			onDownloadStateChange(DownloadState.Idle)
		}
	}

	val isDownloading = downloadState is DownloadState.Downloading ||
		downloadState is DownloadState.Extracting ||
		downloadState is DownloadState.Verifying

	val isModelInstalled = remember(draft.modelDirectory, downloadState) {
		val p = runCatching { java.nio.file.Path.of(draft.modelDirectory) }.getOrNull()
		(p != null && ModelDownloader.isModelInstalled(p)) || ModelDownloader.isModelInstalled()
	}

	val canApply = !isDownloading && (draft.scale == 1 || (
		draft.python.isNotBlank() &&
		draft.nunifDirectory.isNotBlank() &&
		draft.modelDirectory.isNotBlank()
	))

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(colors.scrim)
			.scrimDismiss(enabled = !isUpscaling && !isDownloading) { onDismiss() },
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(520.dp)
				.heightIn(max = 660.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			// Dialog Header
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = tr("upscale.title"),
						style = typography.title.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
				}
				CompactIconButton(onClick = onDismiss, enabled = !isUpscaling && !isDownloading, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}

			// Scrollable Form Body
			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.verticalScroll(scrollState),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = tr("upscale.description"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textMuted,
				)

				// Model Availability Status & Download Card
				if (isDownloading) {
					if (downloadState is DownloadState.Downloading) {
						CompactButton(
							text = tr("upscale.cancel"),
							onClick = { cancelFlag.set(true) },
							height = 22.dp,
						)
					}
				} else if (isModelInstalled) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(4.dp))
							.background(colors.highlightContainer.copy(alpha = 0.5f))
							.border(BorderStroke(1.dp, colors.highlight.copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
							.padding(horizontal = 10.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Text("✓", color = colors.highlight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
							Text(
								text = tr("upscale.modelReady"),
								style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
								color = colors.highlight,
							)
						}
						Text(
							text = "swin_unet_v3 / art",
							style = typography.monoSmall.copy(fontSize = 9.sp),
							color = colors.textMuted,
						)
					}
				} else {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(6.dp))
							.background(colors.inputBackground)
							.border(BorderStroke(1.dp, if (downloadState is DownloadState.Failed) colors.error else colors.border), RoundedCornerShape(6.dp))
							.padding(10.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						when (val state = downloadState) {
							is DownloadState.Failed -> {
								Text(
									text = "${tr("upscale.downloadFailed")}: ${state.error}",
									style = typography.caption.copy(fontSize = 10.sp),
									color = colors.error,
								)
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.End,
								) {
									CompactButton(
										text = tr("upscale.retryDownload"),
										isPrimary = true,
										onClick = {
											cancelFlag.set(false)
											coroutineScope.launch {
												ModelDownloader.downloadAndInstall(cancelFlag) { s ->
													downloadState = s
													onDownloadStateChange(s)
													if (s is DownloadState.Success) {
														val autoPython = draft.python.ifBlank { TextureUpscaleConfig.detectAvailablePython() }
														draft = draft.copy(
															modelDirectory = s.modelDir.toString(),
															nunifDirectory = s.nunifDir.toString(),
															python = autoPython,
															scale = if (draft.scale == 1) 2 else draft.scale,
														)
													}
												}
											}
										},
										height = 24.dp,
									)
								}
							}
							else -> {
								Row(
									modifier = Modifier.fillMaxWidth(),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween,
								) {
									Column(modifier = Modifier.weight(1f)) {
										Text(
											text = tr("upscale.modelMissingTitle"),
											style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
											color = colors.textPrimary,
										)
										Text(
											text = tr("upscale.modelMissingDesc"),
											style = typography.caption.copy(fontSize = 9.5.sp),
											color = colors.textMuted,
										)
									}
									Spacer(Modifier.width(8.dp))
									CompactButton(
										text = tr("upscale.oneClickDownload"),
										isPrimary = true,
										enabled = !isBusy && !isUpscaling,
										onClick = {
											cancelFlag.set(false)
											coroutineScope.launch {
												ModelDownloader.downloadAndInstall(cancelFlag) { s ->
													downloadState = s
													onDownloadStateChange(s)
													if (s is DownloadState.Success) {
														val autoPython = draft.python.ifBlank { TextureUpscaleConfig.detectAvailablePython() }
														draft = draft.copy(
															modelDirectory = s.modelDir.toString(),
															nunifDirectory = s.nunifDir.toString(),
															python = autoPython,
															scale = if (draft.scale == 1) 2 else draft.scale,
														)
													}
												}
											}
										},
										height = 26.dp,
									)
								}
							}
						}
					}
				}

				if (localRuntime != null) {
					CompactButton(
						text = tr("upscale.local"),
						isPrimary = false,
						enabled = !isBusy,
						onClick = {
							draft = draft.copy(
								python = localRuntime.python,
								nunifDirectory = localRuntime.nunifDirectory,
								modelDirectory = localRuntime.modelDirectory,
							)
						},
						modifier = Modifier.fillMaxWidth(),
					)
				}

				// Scale selector
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("upscale.scale"),
						style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)
					CompactDropdown(
						items = listOf(1, 2, 4),
						selectedItem = draft.scale,
						onItemSelected = { draft = draft.copy(scale = it) },
						itemLabel = {
							if (it == 1) tr("upscale.off")
							else if (it == 4 && hasOnly2x) "4× (${tr("upscale.cascaded")})"
							else "${it}×"
						},
						enabled = !isBusy,
						modifier = Modifier.width(if (hasOnly2x) 165.dp else 130.dp),
					)
				}

				// Noise & Sharpening selector
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = tr("upscale.noiseLevel"),
							style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
							color = colors.textPrimary,
						)
						Text(
							text = tr("upscale.noiseLevelDesc"),
							style = typography.caption.copy(fontSize = 9.5.sp),
							color = colors.textMuted,
						)
					}
					Spacer(Modifier.width(8.dp))
					CompactDropdown(
						items = listOf(1, 0, 2, 3, -1),
						selectedItem = draft.noiseLevel,
						onItemSelected = { draft = draft.copy(noiseLevel = it) },
						itemLabel = {
							when (it) {
								0 -> tr("upscale.noiseLevel.0")
								1 -> tr("upscale.noiseLevel.1")
								2 -> tr("upscale.noiseLevel.2")
								3 -> tr("upscale.noiseLevel.3")
								else -> tr("upscale.noiseLevel.none")
							}
						},
						enabled = !isBusy && draft.scale > 1,
						modifier = Modifier.width(200.dp),
					)
				}

				// Form fields
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.python"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.python,
						onValueChange = { draft = draft.copy(python = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.repo"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.nunifDirectory,
						onValueChange = { draft = draft.copy(nunifDirectory = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.model"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.modelDirectory,
						onValueChange = { draft = draft.copy(modelDirectory = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				// Tile size
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("upscale.tile"),
						style = typography.body.copy(fontSize = 11.sp),
						color = colors.textMuted,
					)
					CompactDropdown(
						items = listOf(64, 128, 256, 512),
						selectedItem = draft.tileSize,
						onItemSelected = { draft = draft.copy(tileSize = it) },
						itemLabel = { "$it px" },
						enabled = !isBusy,
						modifier = Modifier.width(130.dp),
					)
				}

				// Neural alpha
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					CompactCheckbox(
						checked = draft.neuralAlpha,
						onCheckedChange = { draft = draft.copy(neuralAlpha = it) },
						label = tr("upscale.neuralAlpha"),
						enabled = !isBusy && draft.scale > 1,
					)
					Text(
						text = tr("upscale.neuralAlphaDesc"),
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
						modifier = Modifier.padding(start = 22.dp),
					)
				}

				Text(
					text = tr("upscale.setup"),
					style = typography.caption.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
				)
			}

			// Footer Buttons
			Row(
				modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
				horizontalArrangement = Arrangement.End,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(
					text = tr("upscale.cancel"),
					isPrimary = false,
					enabled = !isUpscaling && !isDownloading,
					onClick = onDismiss,
				)
				Spacer(Modifier.width(8.dp))
				CompactButton(
					text = tr("upscale.apply"),
					isPrimary = true,
					enabled = !isBusy && !isDownloading && canApply,
					onClick = {
						onApply(
							draft.copy(
								python = draft.python.trim(),
								nunifDirectory = draft.nunifDirectory.trim(),
								modelDirectory = draft.modelDirectory.trim(),
							)
						)
						onDismiss()
					},
				)
			}
		}
	}
}
