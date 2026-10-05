package io.github.psd2live.application

import io.github.psd2live.project.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId

/** Fast auxiliary commands use the same CAS boundary for desktop gestures and external agents. */
interface WorkspaceAuxiliaryPort {
    fun savedProjectData(): WorkspaceAuxiliarySnapshot
    fun editSavedProjectData(state: String, edit: WorkspaceAuxiliaryEdit): WorkspaceAuxiliarySnapshot
    fun applySavedSnapshotPose(state: String, id: String): kotlinx.serialization.json.JsonObject
}

data class WorkspaceAuxiliarySnapshot(val projectId: String, val state: String, val historyNodeId: String,
                                      val data: WorkspaceAuxiliaryData, val historyNodeIds: Set<String>)

sealed interface WorkspaceAuxiliaryEdit {
    data class CreateSnapshot(val id: String, val name: String, val values: Map<ParameterId, Float>) : WorkspaceAuxiliaryEdit
    data class UpdateSnapshot(val id: String, val name: String? = null, val values: Map<ParameterId, Float>? = null) : WorkspaceAuxiliaryEdit
    data class DeleteSnapshot(val id: String) : WorkspaceAuxiliaryEdit
    data class PutAnnotation(val nodeId: String, val annotation: HistoryAnnotation) : WorkspaceAuxiliaryEdit
    data class DeleteAnnotation(val nodeId: String) : WorkspaceAuxiliaryEdit
}

object WorkspaceAuxiliaryEdits {
    fun apply(current: WorkspaceAuxiliaryData, edit: WorkspaceAuxiliaryEdit,
              parameters: List<Parameter>, historyNodeIds: Set<String>): WorkspaceAuxiliaryData {
        fun validate(values: Map<ParameterId, Float>) {
            val known = parameters.associateBy { it.id }
            values.forEach { (id, value) ->
                val parameter = requireNotNull(known[id]) { "Unknown parameter: ${id.raw}" }
                require(value.isFinite() && value in parameter.min..parameter.max) { "${id.raw} outside parameter range" }
            }
        }
        fun requireSnapshot(id: String) = require(current.parameterSnapshots.any { it.id == id }) { "Snapshot not found: $id" }
        fun requireNode(id: String) = require(id in historyNodeIds) { "History node not found: $id" }
        return when (edit) {
            is WorkspaceAuxiliaryEdit.CreateSnapshot -> {
                require(edit.id.isNotBlank() && current.parameterSnapshots.none { it.id == edit.id }) { "Snapshot ID must be new and nonempty" }
                validate(edit.values)
                val number = (current.parameterSnapshots.maxOfOrNull { it.number } ?: 0) + 1
                current.copy(parameterSnapshots = current.parameterSnapshots + ParameterSnapshot(edit.id, number, edit.name.trim(), edit.values.toMap()))
            }
            is WorkspaceAuxiliaryEdit.UpdateSnapshot -> {
                requireSnapshot(edit.id)
                require(edit.name != null || edit.values != null) { "Provide a name or replacement values" }
                edit.values?.let(::validate)
                current.copy(parameterSnapshots = current.parameterSnapshots.map { if (it.id == edit.id)
                    it.copy(name = edit.name?.trim() ?: it.name, values = edit.values?.toMap() ?: it.values) else it })
            }
            is WorkspaceAuxiliaryEdit.DeleteSnapshot -> {
                requireSnapshot(edit.id)
                current.copy(parameterSnapshots = current.parameterSnapshots.filterNot { it.id == edit.id })
            }
            is WorkspaceAuxiliaryEdit.PutAnnotation -> {
                requireNode(edit.nodeId)
                current.copy(historyAnnotations = current.historyAnnotations + (edit.nodeId to edit.annotation.copy(title = edit.annotation.title.trim())))
            }
            is WorkspaceAuxiliaryEdit.DeleteAnnotation -> {
                requireNode(edit.nodeId)
                current.copy(historyAnnotations = current.historyAnnotations - edit.nodeId)
            }
        }
    }
}
