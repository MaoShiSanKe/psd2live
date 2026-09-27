package io.github.psd2live.agent

import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The panel's physics actions as MCP reaches them: import, fit, order and rate, through history and back. */
class PhysicsWorkspaceTest {
    @TempDir lateinit var temp: Path

    @Test fun importFitOrderAndRateCommitAndRestore() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { viewModel ->
            ViewModelAgentWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachAgentWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 16); put("height", 16)
                    putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "decoration"); put("role", "objects") }) }
                })
                fun group(id: String, output: String) = RigPhysicsEdit(id, id,
                    listOf(PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X), PhysicsInput("ParamAngleZ", 60f, PhysicsSourceType.ANGLE)),
                    listOf(PhysicsOutput(output, 1, 0.3f)), listOf(PhysicsSegment(8f, 0.9f, 0.9f, 1.2f)))
                val file = temp.resolve("model.physics3.json")
                Files.writeString(file, Physics3Json.write(listOf(group("Ribbon", "ParamHairFront"), group("Bow", "ParamHairBack")), 30)!!)

                val (imported, report) = workspace.importPhysics(file.toString(), created.historyNodeId)
                assertEquals(listOf("Ribbon", "Bow"), report.getValue("imported").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(30, workspace.physicsFps())
                assertEquals(listOf("Ribbon", "Bow"), workspace.listPhysics().filter { it.active }.map { it.id }.takeLast(2))

                val fitted = workspace.fitPhysics("Ribbon", 1f, imported.historyNodeId)
                val scale = workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale
                assertNotEquals(0.3f, scale)

                val configured = workspace.configurePhysics(listOf("Bow"), 120, fitted.historyNodeId)
                assertEquals("Bow", workspace.listPhysics().first().id)
                assertEquals(120, workspace.physicsFps())
                assertFailsWith<IllegalArgumentException> { workspace.configurePhysics(null, 0, configured.historyNodeId) }

                // History restores the order and rate with the groups.
                workspace.checkoutHistory(imported.historyNodeId, MutationAuthor.AGENT)
                assertEquals(30, workspace.physicsFps())
                assertEquals(0.3f, workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale)
                workspace.checkoutHistory(configured.historyNodeId, MutationAuthor.AGENT)
                assertEquals(120, workspace.physicsFps())
                assertEquals("Bow", workspace.listPhysics().first().id)
                assertEquals(scale, workspace.listPhysics().first { it.id == "Ribbon" }.setting.outputs.single().scale)
            }
        }
    }
}
