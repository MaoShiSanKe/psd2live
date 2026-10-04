package io.github.psd2live.application

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*

data class WorkspacePose(
    val values: Map<ParameterId, Float>,
    val locked: Set<ParameterId>,
)

/** Parameter preview never authors forms or timeline keys; recording a pose is a separate command. */
internal object PreviewSessions {
    fun read(parameters: List<Parameter>, auxiliary: JsonObject, workspaceId: String?): WorkspacePose {
        val saved = workspaceId?.let { auxiliary["posesByWorkspace"]?.jsonObject?.get(it)?.jsonObject }
        val values = saved?.get("values")?.jsonObject.orEmpty()
        val locks = saved?.get("locked")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
        return normalize(parameters, WorkspacePose(values.mapNotNull { (id, value) ->
            value.jsonPrimitive.floatOrNull?.let { ParameterId(id) to it }
        }.toMap(), locks.mapTo(HashSet(), ::ParameterId)))
    }

    /** Normalize old saved poses against the captured model without rewriting their archive. */
    fun normalize(parameters: List<Parameter>, pose: WorkspacePose) = WorkspacePose(parameters.associate { parameter ->
        parameter.id to (pose.values[parameter.id]?.takeIf { it.isFinite() }?.coerceIn(parameter.min, parameter.max) ?: parameter.default)
    }, pose.locked.intersect(parameters.mapTo(HashSet()) { it.id }))

    fun encode(pose: WorkspacePose) = buildJsonObject {
        putJsonObject("values") { pose.values.toSortedMap(compareBy { it.raw }).forEach { (id, value) -> put(id.raw, value) } }
        putJsonArray("locked") { pose.locked.map { it.raw }.sorted().forEach { add(it) } }
    }

    fun edit(
        parameters: List<Parameter>,
        current: WorkspacePose,
        values: Map<String, Float> = emptyMap(),
        locks: Map<String, Boolean> = emptyMap(),
        reset: Boolean = false,
    ): WorkspacePose {
        val definitions = parameters.associateBy { it.id.raw }
        require((values.keys + locks.keys).all { it in definitions }) { "Unknown preview parameter" }
        values.forEach { (id, value) ->
            val parameter = definitions.getValue(id)
            require(value.isFinite() && value in parameter.min..parameter.max) { "$id outside parameter range" }
        }
        if (reset) {
            require(values.isEmpty() && locks.isEmpty()) { "Reset does not accept values or locks" }
            return WorkspacePose(parameters.associate { it.id to it.default }, emptySet())
        }
        require(values.isNotEmpty() || locks.isNotEmpty()) { "Provide values or locks" }
        var locked = current.locked
        locks.forEach { (id, value) ->
            val parameterId = definitions.getValue(id).id
            locked = if (value) locked + parameterId else locked - parameterId
        }
        return WorkspacePose(current.values + values.mapKeys { definitions.getValue(it.key).id }, locked)
    }
}
