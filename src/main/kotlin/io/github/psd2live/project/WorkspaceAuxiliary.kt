package io.github.psd2live.project

import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Saved poses belong to the portable project, not to the exported rig or rig history. */
data class ParameterSnapshot(val id: String, val number: Int, val name: String, val values: Map<ParameterId, Float>)
data class HistoryAnnotation(val title: String = "", val note: String = "", val hidden: Boolean = false)

data class WorkspaceAuxiliaryData(
    val parameterSnapshots: List<ParameterSnapshot> = emptyList(),
    val historyAnnotations: Map<String, HistoryAnnotation> = emptyMap(),
)

/** These field names are the existing v1 presentation format; loading preserves IDs and numbers. */
object WorkspaceAuxiliaryCodec {
    fun encode(value: WorkspaceAuxiliaryData): JsonObject = buildJsonObject {
        putJsonArray("parameterSnapshots") { value.parameterSnapshots.forEach { snapshot -> add(buildJsonObject {
            put("id", snapshot.id); put("number", snapshot.number); put("name", snapshot.name)
            putJsonObject("values") { snapshot.values.forEach { (id, value) -> put(id.raw, value) } }
        }) } }
        putJsonObject("historyAnnotations") { value.historyAnnotations.forEach { (id, annotation) ->
            putJsonObject(id) { put("title", annotation.title); put("note", annotation.note); put("hidden", annotation.hidden) }
        } }
    }

    fun decode(value: JsonObject, fallback: WorkspaceAuxiliaryData = WorkspaceAuxiliaryData()): WorkspaceAuxiliaryData = WorkspaceAuxiliaryData(
        parameterSnapshots = value["parameterSnapshots"]?.jsonArray?.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            ParameterSnapshot(id, obj["number"]?.jsonPrimitive?.intOrNull ?: index + 1,
                obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                obj["values"]?.jsonObject?.mapNotNull { (parameter, element) ->
                    element.jsonPrimitive.floatOrNull?.takeIf(Float::isFinite)?.let { ParameterId(parameter) to it }
                }?.toMap().orEmpty())
        } ?: fallback.parameterSnapshots,
        historyAnnotations = value["historyAnnotations"]?.jsonObject?.mapValues { (_, element) ->
            val annotation = element.jsonObject
            HistoryAnnotation(annotation.getValue("title").jsonPrimitive.content,
                annotation.getValue("note").jsonPrimitive.content, annotation.getValue("hidden").jsonPrimitive.boolean)
        } ?: fallback.historyAnnotations,
    )
}
