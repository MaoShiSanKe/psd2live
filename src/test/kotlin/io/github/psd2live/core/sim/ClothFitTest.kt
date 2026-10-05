package io.github.psd2live.core.sim

import org.umamo.format.art.LayerRaster
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClothFitTest {
    private val navy = 0x2a3a7a
    private val peach = 0xf8e0d0

    /** A silhouette in [colour] (or the colour [paint] picks), opaque where [paint] gives one. */
    private fun raster(width: Int, height: Int, paint: (x: Int, y: Int) -> Int?): LayerRaster {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val colour = paint(x, y) ?: continue
            val i = (y * width + x) * 4
            rgba[i] = (colour shr 16).toByte(); rgba[i + 1] = (colour shr 8).toByte(); rgba[i + 2] = colour.toByte(); rgba[i + 3] = 255.toByte()
        }
        return LayerRaster(width, height, rgba)
    }

    /** A torso 80 px wide from the shoulders at row 10 to row 150, under a 30 px collar. */
    private fun torso(x: Int, y: Int) = y < 10 && abs(x - 100) < 15 || y in 10 until 150 && abs(x + 0.5f - 100f) < 40f

    @Test fun aFittedTopIsWornTight() {
        val field = ClothFit.analyze(raster(200, 150) { x, y -> navy.takeIf { torso(x, y) } }, 0f, 0f, ClothFit.Wear.TOP)
        assertEquals(0f, field.looseShare)
        for (y in 0 until 150 step 10) assertTrue(field.at(100f, y + 0.5f) < 0.05f && field.at(62f, y + 0.5f) < 0.05f, "row $y")
    }

    @Test fun aBowStandingOutOfTheTorsoIsLoose() {
        // A bow at the waist, 50 px out past the right side.
        val art = raster(200, 150) { x, y -> navy.takeIf { torso(x, y) || y in 100 until 120 && x in 140 until 190 } }
        val field = ClothFit.analyze(art, 0f, 0f, ClothFit.Wear.TOP)
        assertTrue(field.at(188f, 110f) > 0.6f, "bow tip ${field.at(188f, 110f)}")
        assertTrue(field.at(100f, 110f) < 0.05f && field.at(62f, 110f) < 0.05f, "the torso beside it stays")
        assertTrue(field.at(145f, 110f) < field.at(188f, 110f), "looser further out")
    }

    @Test fun aTopHangsBelowTheWaist() {
        // A coat: the torso, then 100 more rows of flaps below a waist at row 150.
        val coat = raster(200, 250) { x, y -> navy.takeIf { torso(x, y) || y >= 150 && abs(x + 0.5f - 100f) < 40f + (y - 150) * 0.2f } }
        val hanging = ClothFit.analyze(coat, 0f, 0f, ClothFit.Wear.TOP, waist = 150f)
        assertEquals(150f, hanging.hangFrom)
        assertTrue(hanging.at(100f, 120f) < 0.05f, "chest held")
        assertTrue(hanging.at(100f, 248f) > 0.9f && hanging.at(65f, 248f) > 0.9f, "the hem swings across its width")
        // Without a waist only the flare counts, and it widens past the body only toward the hem.
        val unknown = ClothFit.analyze(coat, 0f, 0f, ClothFit.Wear.TOP)
        assertNull(unknown.hangFrom)
        assertTrue(unknown.at(100f, 248f) < 0.05f && unknown.at(45f, 248f) > unknown.at(100f, 248f))
    }

    @Test fun straightTrousersAreTightAndBellBottomsFlareAtTheHem() {
        fun trousers(flare: Float) = raster(140, 200) { x, y ->
            val out = if (y > 150) (y - 150) * flare else 0f
            navy.takeIf { if (y < 60) x in 35 until 105 else x + 0.5f in 35f - out..65f || x + 0.5f in 75f..105f + out }
        }
        val straight = ClothFit.analyze(trousers(0f), 0f, 0f, ClothFit.Wear.TROUSERS, waist = 0f)
        assertEquals(0f, straight.looseShare)
        val bell = ClothFit.analyze(trousers(0.6f), 0f, 0f, ClothFit.Wear.TROUSERS, waist = 0f)
        assertTrue(bell.at(8f, 198f) > 0.3f && bell.at(132f, 198f) > 0.3f, "flared hems ${bell.at(8f, 198f)}")
        assertTrue(bell.at(50f, 100f) < 0.05f, "thighs held")
    }

    @Test fun aPencilSkirtIsTightAndAnALineSkirtSwings() {
        fun skirt(flare: Float) = raster(200, 200) { x, y -> navy.takeIf { abs(x + 0.5f - 100f) < 50f + y * flare } }
        val pencil = ClothFit.analyze(skirt(0f), 0f, 0f, ClothFit.Wear.SKIRT, waist = 5f)
        assertEquals(0f, pencil.looseShare)
        assertNull(pencil.hangFrom)
        val aLine = ClothFit.analyze(skirt(0.25f), 0f, 0f, ClothFit.Wear.SKIRT, waist = 5f)
        assertTrue(aLine.hangFrom!! < 100f)
        assertTrue(aLine.at(100f, 198f) > 0.8f, "the hem swings, its middle too")
        assertTrue(aLine.at(100f, 3f) == 0f, "the waist holds")
    }

    @Test fun onASleeveOnlyWhatIsThinnerThanTheArmIsLooseAndTheHandStays() {
        // A diagonal arm 40 px thick, a ribbon 6 px wide hanging 60 px from its middle, a spread hand at its end.
        val art = raster(240, 240) { x, y ->
            val along = (x + y) / 2f
            val across = abs(x - y) / 1.4142f
            when {
                along in 20f..160f && across < 20f -> navy
                abs(x - 125) < 3 && y in 90..190 -> navy
                hypot(x - 190f, y - 190f) < 30f -> peach
                else -> null
            }
        }
        val field = ClothFit.analyze(art, 0f, 0f, ClothFit.Wear.SLEEVE)
        assertTrue(field.at(125f, 185f) > 0.6f, "ribbon tail ${field.at(125f, 185f)}")
        assertTrue(field.at(60f, 60f) < 0.05f && field.at(150f, 150f) < 0.05f, "the arm stays")
        assertTrue(field.at(205f, 205f) < 0.05f, "the hand stays")
    }

    @Test fun skinIsWarmLightAndSoftlySaturated() {
        for ((r, g, b) in listOf(Triple(248, 224, 208), Triple(248, 232, 232), Triple(240, 184, 176), Triple(248, 240, 232))) assertTrue(ClothFit.isSkin(r, g, b), "$r,$g,$b")
        for ((r, g, b) in listOf(Triple(248, 248, 248), Triple(200, 170, 110), Triple(230, 215, 190), Triple(42, 58, 122), Triple(250, 120, 120))) assertFalse(ClothFit.isSkin(r, g, b), "$r,$g,$b")
    }
}
