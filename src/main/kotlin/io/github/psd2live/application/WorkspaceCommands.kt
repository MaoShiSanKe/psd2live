package io.github.psd2live.application

import io.github.psd2live.application.WorkspaceBackend
import kotlinx.serialization.json.*

/** Parsing and command composition are application concerns; no transport request is retained. */
internal data class WorkspaceCommandInput(val arguments: JsonObject)
internal data class WorkspaceCommandSchema(val properties: JsonObject = JsonObject(emptyMap()), val required: List<String> = emptyList()) {
    fun toJson() = buildJsonObject {
        put("type", "object"); put("properties", properties); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
}

internal data class WorkspaceCommandHints(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = false,
)

/** Internal typed command compositions. Public operation definitions select their exact inputs. */
internal class WorkspaceCommands() {
    internal data class Command(val id: String, val description: String, val schema: WorkspaceCommandSchema,
        val hints: WorkspaceCommandHints, val execute: suspend (WorkspaceCommandInput) -> WorkspaceOperationOutput)
    private val commands = linkedMapOf<String, Command>()
    constructor(workspace: WorkspaceBackend) : this() { registerWorkspaceCommands(this, workspace) }

    fun register(name: String, description: String, inputSchema: WorkspaceCommandSchema = WorkspaceCommandSchema(),
                 hints: WorkspaceCommandHints = WorkspaceCommandHints(),
                 execute: suspend (WorkspaceCommandInput) -> WorkspaceOperationOutput) {
        require(name !in commands) { "Command already registered: $name" }
        commands[name] = Command(name, description, inputSchema, hints, execute)
    }

    fun get(id: String): Command = requireNotNull(commands[id]) { "Unknown workspace command: $id" }
    suspend fun invoke(id: String, arguments: JsonObject): WorkspaceOperationOutput = get(id).execute(WorkspaceCommandInput(arguments))
}

internal val READ_ONLY = WorkspaceCommandHints(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
internal val MUTATING = WorkspaceCommandHints()
