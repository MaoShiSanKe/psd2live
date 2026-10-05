package io.github.psd2live.application

import io.github.psd2live.project.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceAuxiliaryEditsTest {
    private val axis = ParameterId("pose")
    private val parameters = listOf(Parameter(axis, "Pose", -1f, 1f, 0f))
    private val nodes = setOf("root", "child")
    private fun edit(data: WorkspaceAuxiliaryData, command: WorkspaceAuxiliaryEdit) =
        WorkspaceAuxiliaryEdits.apply(data, command, parameters, nodes)

    @Test fun savedPosesKeepIdentityAndNumbersAndRejectInvalidEdits() {
        val initial = WorkspaceAuxiliaryData(listOf(ParameterSnapshot("old", 4, "Old", mapOf(axis to 0.2f))))
        val created = edit(initial, WorkspaceAuxiliaryEdit.CreateSnapshot("new", "  Pose  ", mapOf(axis to 0.8f)))
        assertEquals(5, created.parameterSnapshots.last().number)
        assertEquals("Pose", created.parameterSnapshots.last().name)
        val deleted = edit(created, WorkspaceAuxiliaryEdit.DeleteSnapshot("old"))
        assertEquals(5, deleted.parameterSnapshots.single().number)
        assertEquals(created, edit(created, WorkspaceAuxiliaryEdit.UpdateSnapshot("new", name = "Pose")))
        for (values in listOf(mapOf(axis to Float.NaN), mapOf(axis to 2f), mapOf(ParameterId("unknown") to 0f))) {
            assertFailsWith<IllegalArgumentException> { edit(initial, WorkspaceAuxiliaryEdit.UpdateSnapshot("old", values = values)) }
        }
        assertFailsWith<IllegalArgumentException> { edit(initial, WorkspaceAuxiliaryEdit.DeleteSnapshot("missing")) }
        assertFailsWith<IllegalArgumentException> { edit(initial, WorkspaceAuxiliaryEdit.UpdateSnapshot("old")) }
        assertEquals(1, initial.parameterSnapshots.size)
    }

    @Test fun annotationsOnlyTargetExistingHistoryAndDeletingAbsentMetadataIsNoOp() {
        val initial = WorkspaceAuxiliaryData()
        val updated = edit(initial, WorkspaceAuxiliaryEdit.PutAnnotation("root", HistoryAnnotation("  Ready ", "Note", true)))
        assertEquals(HistoryAnnotation("Ready", "Note", true), updated.historyAnnotations.getValue("root"))
        assertEquals(updated, edit(updated, WorkspaceAuxiliaryEdit.DeleteAnnotation("child")))
        assertFailsWith<IllegalArgumentException> { edit(updated, WorkspaceAuxiliaryEdit.PutAnnotation("missing", HistoryAnnotation())) }
        assertEquals(initial, edit(updated, WorkspaceAuxiliaryEdit.DeleteAnnotation("root")))
    }

    @Test fun auxiliaryCodecPreservesLegacyIdsNumbersAndValidValues() {
        val legacy = buildJsonObject {
            putJsonArray("parameterSnapshots") {
                add(buildJsonObject { put("id", "legacy"); put("name", "Pose"); putJsonObject("values") { put("pose", 0.8) } })
                add(buildJsonObject { put("id", "explicit"); put("number", 7); putJsonObject("values") { put("pose", "invalid") } })
            }
            putJsonObject("historyAnnotations") { putJsonObject("root") { put("title", "Keep"); put("note", ""); put("hidden", false) } }
        }
        val decoded = WorkspaceAuxiliaryCodec.decode(legacy)
        assertEquals(listOf(1, 7), decoded.parameterSnapshots.map { it.number })
        assertEquals(mapOf(axis to 0.8f), decoded.parameterSnapshots.first().values)
        assertEquals(emptyMap(), decoded.parameterSnapshots.last().values)
        assertEquals(decoded, WorkspaceAuxiliaryCodec.decode(WorkspaceAuxiliaryCodec.encode(decoded)))
        assertEquals(decoded, WorkspaceAuxiliaryCodec.decode(buildJsonObject {}, decoded))
    }
}
