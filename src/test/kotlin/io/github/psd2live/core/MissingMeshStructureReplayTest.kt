package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MissingMeshStructureReplayTest {
    private fun model(withMesh: Boolean) = PuppetModel(
        parameters = emptyList(), parts = emptyList(), deformers = emptyList(),
        drawables = if (withMesh) listOf(Drawable(DrawableId("ArtMeshMouth2"), "Mouth", null,
            BlendMode.Normal, emptyList(), null, null)) else emptyList(),
        rootChildren = emptyList(), rootPartId = null,
    )

    private val rename = buildJsonObject {
        put("action", "rename"); put("kind", "mesh"); put("id", "ArtMeshMouth2"); put("name", "Edited mouth")
    }

    @Test fun missingMeshEditIsSkippedDuringReplayButRestoredWhenMeshReturns() {
        val edits = RigEditOverlay(structureEdits = listOf(rename))
        assertEquals(emptyList(), edits.applyTo(model(withMesh = false)).drawables)
        assertEquals("Edited mouth", edits.applyTo(model(withMesh = true)).drawables.single().name)

        val journalEdit = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") { add(rename) }
        }
        val journal = RigEditOverlay(authoringJournal = listOf(journalEdit))
        assertEquals(emptyList(), journal.applyTo(model(withMesh = false)).drawables)
        assertEquals("Edited mouth", journal.applyTo(model(withMesh = true)).drawables.single().name)

        assertFailsWith<IllegalArgumentException> { RigStructureEdits.apply(model(withMesh = false), listOf(rename)) }
        assertFailsWith<IllegalArgumentException> {
            RigAuthoringJournal.compile(model(withMesh = false), JsonArray(listOf(journalEdit)))
        }
    }
}
