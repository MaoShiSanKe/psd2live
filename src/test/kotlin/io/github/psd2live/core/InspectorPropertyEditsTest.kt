package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorPropertyEditsTest {
    private fun model() = PuppetModel(
        parameters = emptyList(),
        parts = emptyList(),
        deformers = emptyList(),
        drawables = listOf(Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), null, null)),
        rootChildren = emptyList(),
        rootPartId = null,
    )

    private fun userData(value: String) = buildJsonObject {
        put("op", JsonPrimitive("structure"))
        put("edits", JsonArray(listOf(buildJsonObject {
            put("action", JsonPrimitive("static"))
            put("kind", JsonPrimitive("mesh"))
            put("id", JsonPrimitive("mesh"))
            put("user_data", JsonPrimitive(value))
        })))
    }

    @Test
    fun userDataIsDurableAndRepeatedWriteCompilesToNoOp() {
        val before = model()
        val command = userData("author note")
        val after = RigAuthoringJournal.apply(before, command)

        assertEquals("author note", after.drawables.single().userData)
        assertTrue(RigAuthoringJournal.compile(after, JsonArray(listOf(command))).second.isEmpty())
    }
}
