package io.github.psd2live.ui.state

import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.core.Side
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class StartScreenViewModelTest {
    @TempDir lateinit var temp: Path

    @Test
    fun autoDetectPreferenceSettingCanBeToggledAndReset() {
        val original = AppSettings.autoDetectMeshSplitsOnImport
        try {
            PSD2LiveViewModel().use { vm ->
                vm.setAutoDetectMeshSplitsOnImport(false)
                assertFalse(AppSettings.autoDetectMeshSplitsOnImport)
                assertFalse(vm.state.value.autoDetectMeshSplitsOnImport)

                vm.setAutoDetectMeshSplitsOnImport(true)
                assertTrue(AppSettings.autoDetectMeshSplitsOnImport)
                assertTrue(vm.state.value.autoDetectMeshSplitsOnImport)

                vm.setAutoDetectMeshSplitsOnImport(false)
                vm.resetInteractionPrefs()
                assertTrue(AppSettings.autoDetectMeshSplitsOnImport)
                assertTrue(vm.state.value.autoDetectMeshSplitsOnImport)
            }
        } finally {
            AppSettings.autoDetectMeshSplitsOnImport = original
        }
    }

    @Test
    fun dismissAllMeshSplitsClearsAllPendingOffersAndQueue() {
        PSD2LiveViewModel().use { vm ->
            vm.dismissAllMeshSplits()
            assertNull(vm.pendingMeshSplit)
            assertNull(vm.pendingStartScreen)
        }
    }

    @Test
    fun disabledAutoDetectSkipsImportScanning() {
        val original = AppSettings.autoDetectMeshSplitsOnImport
        try {
            AppSettings.autoDetectMeshSplitsOnImport = false
            PSD2LiveViewModel().use { vm ->
                vm.offerImportMeshSplit(listOf("layer-1", "layer-2"))
                assertNull(vm.pendingMeshSplit)
                assertNull(vm.pendingStartScreen)
            }
        } finally {
            AppSettings.autoDetectMeshSplitsOnImport = original
        }
    }

    @Test
    fun startScreenNeedsAModel() {
        PSD2LiveViewModel().use { vm ->
            vm.requestStartScreen(fresh = true)
            assertNull(vm.pendingStartScreen)
        }
    }

    @Test
    fun quickPresetsDifferOnlyWhereThePartsAllow() {
        // The default turns clothing on; a fresh state has no clothing simulation yet.
        assertTrue(StartQuickPreset.DEFAULT.choices.clothing)
        assertEquals(StartQuickPreset.DEFAULT.choices.copy(clothing = false), StartPresetChoices.of(PSD2LiveState()))

        val bare = PresetParts(frontHair = false, backHair = false, clothing = false, eyeJelly = false)
        assertEquals(StartQuickPreset.DEFAULT.choices.within(bare), StartQuickPreset.FULL.choices.copy(featureDisplacement = false).within(bare))
        assertNotEquals(StartQuickPreset.MINIMAL.choices.within(bare), StartQuickPreset.DEFAULT.choices.within(bare))
        val full = PresetParts(frontHair = true, backHair = true, clothing = true, eyeJelly = true)
        assertEquals(HairMode.SIMULATION, StartQuickPreset.FULL.choices.within(full).frontHair)
        assertEquals(HairMode.OFF, StartQuickPreset.FULL.choices.within(bare).frontHair)
    }

    /** Two squares in one layer: the start screen offers the split, then applies it and the minimal presets. */
    @Test
    fun startScreenSplitsThenAppliesPresets() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(96, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 8..39) {
            for (x in 6..34) image.setRGB(x, y, 0xffff3366.toInt())
            for (x in 60..88) image.setRGB(x, y, 0xffff3366.toInt())
        }
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 96); put("height", 48)
                    putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "deco"); put("role", "objects") }) }
                })
                val layerId = vm.state.value.analysis!!.source.layers.single().id.raw

                vm.requestStartScreen(fresh = true)
                waitUntil { vm.pendingStartScreen?.splits != null }
                val offer = assertNotNull(vm.pendingStartScreen)
                assertTrue(offer.presets)
                assertEquals(StartQuickPreset.DEFAULT.choices, offer.initial)
                val split = offer.splits!!.single()
                assertEquals(layerId, split.layerId)
                assertEquals(2, split.plan.components.size)

                vm.applyStartScreen(StartQuickPreset.MINIMAL.choices,
                    listOf(PSD2LiveViewModel.LayerSplitDecision(split, listOf("deco-a", "deco-b"), listOf(Side.NONE, Side.NONE))))
                assertNull(vm.pendingStartScreen)
                // Preset switches update asynchronously; one early switch does not mark the complete choice set.
                waitUntil {
                    val current = vm.state.value
                    val parts = PresetParts.of(current.analysis)
                    current.analysis!!.layers.size == 2 &&
                        StartPresetChoices.of(current).within(parts) == StartQuickPreset.MINIMAL.choices.within(parts)
                }
                val state = vm.state.value
                assertEquals(setOf("deco-a", "deco-b"), state.analysis!!.layers.map { it.source.name }.toSet())
                assertTrue(layerId in state.deletedLayerIds)
                assertFalse(state.motionSkeleton)
                assertFalse(state.mouthOutlineEnabled)
                assertFalse(state.physicsEyeJelly)
                assertFalse(state.physicsFrontHair)
                assertEquals(StartQuickPreset.MINIMAL.choices.within(PresetParts.of(state.analysis)),
                    StartPresetChoices.of(state).within(PresetParts.of(state.analysis)))
            }
        }
    }

    private fun waitUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the start screen" }
            Thread.sleep(20)
        }
    }
}
