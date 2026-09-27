package org.umamo.interop

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

class PuppetModelUserDataDiffTest {
    @Test
    fun drawableUserDataIsReportedForExportLowering() {
        val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), null, null)
        val baseline = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(drawable), emptyList(), null)
        val edited = baseline.copy(drawables = listOf(drawable.copy(userData = "note")))

        val changed = diffPuppetModels(baseline, edited).drawables.single() as EntityDiff.Changed
        assertEquals(setOf(DrawableField.USER_DATA), changed.fields)
    }
}
