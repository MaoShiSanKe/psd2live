package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshFootprintRigTest {
    private fun source(): WorkspaceSourceArt {
        val size = 128
        val rgba = ByteArray(size * size * 4)
        fun pixel(x: Int, y: Int) {
            val i = (y * size + x) * 4
            rgba[i] = 60
            rgba[i + 1] = 80
            rgba[i + 2] = 100
            rgba[i + 3] = -1
        }
        for (y in 20..68) for (x in 20..68) pixel(x, y)
        pixel(115, 112) // An isolated background-removal remnant, far from the artwork.
        val layer = WorkspaceSourceLayer(
            LayerId("face"), "face", "", SourceLayerKind.Raster, true, 0,
            LayerBounds(0, 0, size, size), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(size, size, rgba), null, null, false,
        )
        return WorkspaceSourceArt(size, size, listOf(layer), emptyList())
    }

    @Test
    fun generatedDeformerFrameAndRenderFollowMeshInsteadOfStrayPixel() {
        val source = source()
        val config = PipelineConfig()
        val analysis = CharacterAnalyzer.analyze(source, config)
        assertTrue(analysis.layers.single().bounds.right > 115f)

        val context = RigBuilder.rigContext(analysis, config, PreviewMeshCache())
        val footprint = context.analysis.layers.single().bounds
        assertTrue(footprint.right < 80f, "Deformer frame should ignore the isolated pixel")
        assertTrue(footprint.bottom < 80f)
        assertTrue(context.character.right < 85f)
        assertTrue(context.face.right < 85f)

        val preview = PSD2LivePipeline().buildPreview(analysis, config)
        val image = BufferedImage(source.widthPx, source.heightPx, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            RigCanvasSupport.paintTexturedRig(
                graphics, preview, RigCanvasSupport.evaluate(preview),
                CanvasViewport(1.0, 0.0, 0.0, source.widthPx.toFloat(), source.heightPx.toFloat()),
            )
        } finally {
            graphics.dispose()
        }
        assertTrue((image.getRGB(40, 40) ushr 24) != 0, "Artwork inside the mesh should render")
        assertEquals(0, image.getRGB(115, 112) ushr 24, "Pixels outside the mesh must not render")
    }
}
