package io.github.psd2live.core.sim

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigAuthoringJournal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimParameterPanelTest {
    private val x = ParameterId("ParamSims_1")
    private val y = ParameterId("ParamSims_2")
    private val base = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
    private val sim = RigSimEdit("s", "Cloth", SimKind.CLOTH, listOf("cloth"), blendShapes = false,
        bake = SimBakeResult("test", emptyMap(), emptyList(), listOf(x, y).map {
            SimBakedMode(SimBakedAxis(it.raw, floatArrayOf(-1f, 0f, 1f), emptyMap()), 1f, 1f)
        }))

    private val folder = buildJsonObject {
        put("action", "create"); put("kind", "param_group"); put("id", "g"); put("name", "Group")
    }
    private fun move(id: ParameterId) = buildJsonObject {
        put("action", "move"); put("kind", "parameter"); put("id", id.raw); put("parent_id", "g")
    }
    private fun link(enabled: Boolean) = buildJsonObject {
        put("action", "link"); put("kind", "parameter"); put("id", x.raw)
        put("partner_id", y.raw); put("linked", enabled)
    }

    @Test fun generatedAxesCanMoveAndLinkAcrossRebuilds() {
        val overlay = RigEditOverlay(simEdits = listOf(sim), structureEdits = listOf(folder, move(x), move(y), link(true)))
        repeat(2) {
            val model = overlay.applyTo(base)
            assertEquals(listOf(ParameterLink(x, y)), model.parameterLinks)
            val group = assertIs<ParameterNode.Group>(model.parameterTree.first())
            assertEquals("g", group.id.raw)
            assertEquals(listOf(x, y), group.children.map { assertIs<ParameterNode.Param>(it).id })
        }
        val unlinked = overlay.copy(structureEdits = overlay.structureEdits + link(false)).applyTo(base)
        assertTrue(unlinked.parameterLinks.isEmpty())
        assertEquals(listOf(x, y), assertIs<ParameterNode.Group>(unlinked.parameterTree.first()).children
            .map { assertIs<ParameterNode.Param>(it).id })
    }

    @Test fun editorJournalKeepsMovesAndLinksAfterSavingAndRebuilding() {
        checkEditorJournal(sim)
    }

    @Test fun bakedBlendShapeAxesCanAlsoMoveAndLinkWithoutChangingTheirKind() {
        checkEditorJournal(sim.copy(blendShapes = true), ParameterKind.BLEND_SHAPE)
    }

    private fun checkEditorJournal(simulation: RigSimEdit, expectedKind: ParameterKind = ParameterKind.NORMAL) {
        var overlay = RigEditOverlay(simEdits = listOf(simulation))
        // Use the same compile -> persist -> rebuild path as the editor's authorRig call.
        for (edit in listOf(folder, move(x), move(y), link(true), link(false), link(true))) {
            val command = buildJsonObject {
                put("op", "structure"); put("edits", JsonArray(listOf(edit)))
            }
            val (edited, journal) = RigAuthoringJournal.compile(overlay.applyTo(base), JsonArray(listOf(command)))
            overlay = overlay.copy(authoringJournal = overlay.authoringJournal + journal)
            val rebuilt = overlay.applyTo(base)
            assertEquals(edited.parameterTree, rebuilt.parameterTree)
            assertEquals(edited.parameters, rebuilt.parameters)
            assertEquals(edited.parameterLinks, rebuilt.parameterLinks)
        }
        assertEquals(listOf(ParameterLink(x, y)), overlay.applyTo(base).parameterLinks)
        assertTrue(overlay.applyTo(base).parameters.all { it.kind == expectedKind })
    }
}
