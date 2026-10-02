package io.github.psd2live.tools

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.components.DepthSplitDialog
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import org.umamo.format.art.*
import java.io.File
import kotlin.test.Test

/** Native Compose previews: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*DepthSplitDialogTool'. */
class DepthSplitDialogTool {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun renderDialog() {
        requireTools()
        val previousLanguage = I18n.currentLanguage
        I18n.setLanguage(AppLanguage.CHINESE, persist = false)
        try {
            val bounds = LayerBounds(20, 20, 40, 40)
            fun layer(id: String, name: String, order: Int): WorkspaceSourceLayer = WorkspaceSourceLayer(
                LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(40, 40, ByteArray(40 * 40 * 4) { -1 }), null, null, false)
            val preview = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(100, 100,
                listOf(layer("collar", "领子", 0), layer("neck", "脖子", 1)), emptyList()), PipelineConfig(atlasSize = 256))
            val ids = preview.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
            val offer = PSD2LiveViewModel.DepthSplitOffer(preview, ids.getValue("collar"), "preview", "preview", ids.getValue("neck"))
            for ((name, colors, fontScale) in listOf(Triple("dark", ToolColors.Dark, 1f),
                Triple("light", ToolColors.Light, 1f), Triple("large-text", ToolColors.Dark, 1.35f))) {
                val scene = ImageComposeScene(720, 560, density = Density(1f)) {
                    CompactToolTheme(colors = colors, fontScale = fontScale) {
                        DepthSplitDialog(offer, onConfirm = {}, onDismiss = {})
                    }
                }
                try {
                    val rendered = scene.render()
                    try {
                        File(output("depth-split"), "$name.png").writeBytes(requireNotNull(rendered.encodeToData()).bytes)
                    } finally { rendered.close() }
                } finally { scene.close() }
            }
        } finally { I18n.setLanguage(previousLanguage, persist = false) }
    }
}
