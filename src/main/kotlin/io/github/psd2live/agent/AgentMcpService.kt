package io.github.psd2live.agent


import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.application.toJson

import io.github.psd2live.application.WorkspaceBackend

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
// A 4096 x 4096 RGBA PNG can approach 64 MiB; Base64 adds another third, plus JSON overhead.
internal const val DEFAULT_MCP_MAX_REQUEST_BODY_BYTES = 96L * 1024 * 1024
private const val SHUTDOWN_GRACE_MILLIS = 100L
private const val SHUTDOWN_TIMEOUT_MILLIS = 500L

data class AgentMcpConfig(
	val host: String = "127.0.0.1",
	val port: Int = 23871,
	val token: String = AgentMcpCredentials.loadOrCreateToken(),
    val maxRequestBodyBytes: Long = DEFAULT_MCP_MAX_REQUEST_BODY_BYTES,
) {
    init { require(maxRequestBodyBytes > 0) { "MCP request body limit must be positive" } }
}

data class AgentMcpConnectionInfo(
	val endpoint: String,
	val token: String,
) {
	val configGeminiJson: String
		get() {
			val endpointJson = JsonPrimitive(endpoint)
			val authorizationJson = JsonPrimitive("Bearer $token")
			return """
				{
				  "mcpServers": {
				    "psd2live": {
				      "serverUrl": $endpointJson,
				      "headers": {
				        "Authorization": $authorizationJson
				      }
				    }
				  }
				}
			""".trimIndent()
		}

	val configToml: String
		get() = """
			[mcp_servers.psd2live]
			url = "$endpoint"
			http_headers = { Authorization = "Bearer $token" }
		""".trimIndent()
}

class AgentMcpService(
	private val workspace: WorkspaceBackend,
	private val config: AgentMcpConfig = AgentMcpConfig(),
) : AutoCloseable {
	private var engine: EmbeddedServer<*, *>? = null
	private val isClosed = AtomicBoolean(false)
    private val operations = WorkspaceOperations(workspace)

	lateinit var connectionInfo: AgentMcpConnectionInfo
		private set

	fun start(): AgentMcpConnectionInfo {
		check(engine == null) { "Agent MCP service is already running" }
		val started = embeddedServer(CIO, configure = {
			connector {
				host = config.host
				port = config.port
			}
			// Ktor stops itself from a JVM shutdown hook too; connected MCP clients hold streams open,
			// so its 1 s grace and 5 s timeout defaults would stall every exit.
			shutdownGracePeriod = SHUTDOWN_GRACE_MILLIS
			shutdownTimeout = SHUTDOWN_TIMEOUT_MILLIS
		}) {
			configureAgentMcp(workspace, config.token, config.maxRequestBodyBytes, operations)
		}
		try {
			started.start(wait = false)
			engine = started
			val actualPort = runBlocking {
				withTimeout(2_000L) {
					val connectors = started.engine.resolvedConnectors()
					connectors.firstOrNull()?.port ?: error("No connectors resolved for MCP server")
				}
			}
			return AgentMcpConnectionInfo(
				endpoint = "http://${config.host}:$actualPort/mcp",
				token = config.token,
			).also { connectionInfo = it }
		} catch (t: Throwable) {
			runCatching { started.stop(gracePeriodMillis = 50, timeoutMillis = 200) }
			engine = null
			throw t
		}
	}

	override fun close() {
		if (!isClosed.compareAndSet(false, true)) return
		runCatching {
			engine?.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS)
		}
		engine = null
        operations.close()
		runCatching {
			(workspace as? AutoCloseable)?.close()
		}
	}
}

object AgentMcpCredentials {
	private const val TOKEN_KEY = "agent_mcp_bearer_token"
	private val preferences: Preferences by lazy { Preferences.userNodeForPackage(AgentMcpCredentials::class.java) }

	fun loadOrCreateToken(): String {
		preferences.get(TOKEN_KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
		val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { preferences.put(TOKEN_KEY, it) }
	}

}

internal fun Application.configureAgentMcp(workspace: WorkspaceBackend, authToken: String, maxRequestBodyBytes: Long = DEFAULT_MCP_MAX_REQUEST_BODY_BYTES, operations: WorkspaceOperations = WorkspaceOperations(workspace)) {
	require(authToken.length >= 32) { "Agent MCP bearer token is too short" }
	install(ContentNegotiation) { json(McpJson) }
    installExactToolPublication(operations.registry)
	install(SSE)
	install(Authentication) {
		bearer("agent-mcp-bearer") {
			authenticate { credential ->
				if (credential.token == authToken) UserIdPrincipal("agent-mcp-client") else null
			}
		}
	}

	val transports = ConcurrentMap<String, StreamableHttpServerTransport>()
	routing {
		authenticate("agent-mcp-bearer") {
			route("/mcp") {
				sse {
					val transport = findTransport(call.request.header(MCP_SESSION_ID_HEADER), transports)
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@sse
					}
					transport.handleRequest(this, call)
				}

				post {
					val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
					val transport = if (sessionId == null) {
						createTransport(workspace, transports, maxRequestBodyBytes, operations)
					} else {
						findTransport(sessionId, transports)
					}
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@post
					}
					transport.handleRequest(null, call)
				}

				delete {
					val transport = findTransport(call.request.header(MCP_SESSION_ID_HEADER), transports)
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@delete
					}
					transport.handleRequest(null, call)
				}
			}
		}
	}
}

private fun findTransport(
	sessionId: String?,
	transports: ConcurrentMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? = sessionId?.let(transports::get)

private suspend fun createTransport(
	workspace: WorkspaceBackend,
	transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    maxRequestBodyBytes: Long,
    operations: WorkspaceOperations,
): StreamableHttpServerTransport {
	val transport = StreamableHttpServerTransport(
		StreamableHttpServerTransport.Configuration(enableJsonResponse = true, maxRequestBodySize = maxRequestBodyBytes),
	)
	transport.setOnSessionInitialized { sessionId -> transports[sessionId] = transport }
	transport.setOnSessionClosed { sessionId -> transports.remove(sessionId) }
	val server = createAgentMcpServer(workspace, operations = operations)
	server.onClose { transport.sessionId?.let(transports::remove) }
	server.createSession(transport)
	return transport
}

internal fun createAgentMcpServer(workspace: WorkspaceBackend, operations: WorkspaceOperations = WorkspaceOperations(workspace)): Server {
	val server = Server(
		serverInfo = Implementation("psd2live", "2.0.4"),
		options = ServerOptions(
			ServerCapabilities(
				resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
				tools = ServerCapabilities.Tools(listChanged = false),
			),
		),
		instructions = AGENT_INSTRUCTIONS,
	)

	server.addResource(
		uri = "psd2live://project/current/manifest",
		name = "Current psd2live project manifest",
		description = "Live structured manifest for the project currently open in psd2live.",
		mimeType = "application/json",
	) { request ->
		ReadResourceResult(
			contents = listOf(TextResourceContents(workspace.snapshot().toJson(true).toString(), request.uri, "application/json")),
		)
	}

    installOperationTools(server, operations.registry)
    return server
}

private val AGENT_INSTRUCTIONS = """
    PSD2Live is a recoverable model editor. Use workspace_list_operations to discover capabilities and workspace_get_operation for exact fields. Use workspace_inspect to inspect context and relevant objects, understand existing motion ownership, and plan your own work. There are no task recipes or required skills.
    Wrap all tool arguments in a request object and follow the published exact schema. Model and PSD exports return process-local job handles; use job_wait/job_get to collect results and job_cancel to cancel explicitly. All mutations require request_id; workspace-bound mutations also require project_id and the opaque state from workspace_inspect. Use null project_id only for creation/import into an unloaded workspace. Retry identical arguments and request_id to recover the original result after a disconnected call. Use a new request_id for changed arguments or expectations.
    Chain the returned project_id and state after document writes. history_node_id identifies a history entry and cannot be used as a state token. A write updates the model and creates history; it is not an uncommitted preview. Reconcile workspace_inspect/history_list after uncertain writes. Preserve useful milestones and restore deliberately.
    Separate source artwork, motion hierarchy, parameter definitions, authored keyforms, and observation poses. Prefer existing owners; add a fitted Warp only for independent motion. All surface points may deform, including empty and boundary cage points. Use broad fields, not isolated mesh vertices.
    Keyform and deformation edits name exact destination keys; viewing parent parameters does not mean binding them again. Plan endpoints, meaningful combinations and intermediate observations. Keep other keys and channels. Physics drives already-authored output forms.
    Generate or edit artwork with available host image tools when needed, then import/register it through asset_import_png and asset_register. Compare actual model renders, never generated illustrations as proof of motion. Structural validity and appearance are different. Report only observed results and remaining limitations.
""".trimIndent()
