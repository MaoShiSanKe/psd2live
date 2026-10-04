package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceSourceEditsTest {
    @TempDir lateinit var temporary: Path

    @Test fun artworkUsesTheDesktopRasterDecoderForPngAndBmpWithCanvasPlacement() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        for (y in 0..1) for (x in 0..2) image.setRGB(x, y, 0xff123456.toInt())
        val paths = listOf("png", "bmp").map { extension ->
            temporary.resolve("art.$extension").also { assertTrue(ImageIO.write(image, extension, it.toFile())) }
        }
        val (source, overrides) = sourceArtwork(buildJsonObject {
            put("width", 8); put("height", 8)
            putJsonArray("layers") { paths.forEachIndexed { index, path -> add(buildJsonObject {
                put("path", path.toString()); put("name", "Artwork $index"); put("x", index + 1); put("y", 2)
                put("role", "objects")
            }) } }
        })
        assertEquals(2, source.layers.size)
        assertContentEquals(source.layers[0].raster.rgba, source.layers[1].raster.rgba)
        assertEquals(listOf(1, 2), source.layers.map { it.bounds.left })
        assertEquals(listOf(1, 0), source.layers.map { it.order })
        assertEquals(source.layers.map { it.id.raw }.toSet(), overrides.keys)
        assertTrue(source.layers.map { it.id }.distinct().size == 2)
    }
}
