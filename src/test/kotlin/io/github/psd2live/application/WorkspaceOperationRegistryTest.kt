package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceOperationRegistryTest {
    private fun objectSchema(properties: JsonObject, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", properties); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    @Test fun publicationAndExecutionUseTheExactSameFieldsAndConstraints() = runBlocking {
        val schema = objectSchema(buildJsonObject {
            put("radius", buildJsonObject { put("type", "number"); put("minimum", 1); put("maximum", 8); put("description", "Canvas pixels") })
        }, listOf("radius"))
        var calls = 0
        val registry = WorkspaceOperationRegistry()
        registry.register(WorkspaceOperationDefinition("paint_brush", "Paint one gesture", schema, WorkspaceOperationKind.DOCUMENT, resultSchema = schema)) { request, context ->
            calls++
            assertEquals(MutationAuthor.USER, context.author)
            WorkspaceOperationOutput(request)
        }
        val published = registry.definitions().single().toJson(includeSchema = true).getValue("request_schema").jsonObject
        assertEquals(schema, published)
        for (invalid in listOf(buildJsonObject {}, buildJsonObject { put("radius", 0) },
            buildJsonObject { put("radius", 3); put("raduis", 3) }, buildJsonObject { put("radius", "3") })) {
            assertFailsWith<WorkspaceValidationException> { registry.invoke("paint_brush", invalid, WorkspaceOperationContext(MutationAuthor.USER)) }
        }
        assertEquals(0, calls)
        val valid = buildJsonObject { put("radius", 3) }
        assertEquals(valid, registry.invoke("paint_brush", valid, WorkspaceOperationContext(MutationAuthor.USER)).data)
        assertEquals(1, calls)
    }

    @Test fun oneOfRequiresExactlyOneFullMatchAndRetainsUsefulDiscriminatorErrors() {
        val create = objectSchema(buildJsonObject {
            put("op", buildJsonObject { put("const", "create") })
            put("id", buildJsonObject { put("type", "string") })
        }, listOf("op", "id"))
        val remove = objectSchema(buildJsonObject {
            put("op", buildJsonObject { put("const", "remove") })
            put("target", buildJsonObject { put("type", "string") })
        }, listOf("op", "target"))
        val schema = buildJsonObject { put("oneOf", JsonArray(listOf(create, remove))) }
        validateOperationSchema(buildJsonObject { put("op", "create"); put("id", "new") }, schema)
        val missing = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("op", "create") }, schema) }
        assertTrue(missing.message!!.contains("id"))
        val ambiguous = buildJsonObject { put("oneOf", JsonArray(listOf(create, create))) }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("op", "create"); put("id", "new") }, ambiguous) }
    }

    @Test fun nullableFieldsAndArrayItemPathsHavePublishedSemantics() {
        val nullable = buildJsonObject { put("type", buildJsonArray { add("integer"); add("null") }); put("minimum", 0) }
        validateOperationSchema(JsonNull, nullable)
        validateOperationSchema(JsonPrimitive(3), nullable)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonPrimitive(-1), nullable) }
        val array = buildJsonObject { put("type", "array"); put("items", nullable); put("minItems", 1) }
        val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonArray { add(1); add("bad") }, array, "request.colors") }
        assertEquals("request.colors[1]", failure.fieldPath)
    }

    @Test fun exclusiveNumericBoundsAreEnforcedAsPublished() {
        val schema = buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0); put("exclusiveMaximum", 1) }
        checkOperationSchema(schema)
        validateOperationSchema(JsonPrimitive(0.5), schema)
        for (invalid in listOf(0.0, 1.0, -0.1, 1.1)) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonPrimitive(invalid), schema) }
        }
    }

    @Test fun uniqueArrayItemsUseStructuralJsonEqualityAndReportTheDuplicateItemPath() {
        val schema = buildJsonObject { put("type", "array"); put("uniqueItems", true) }
        checkOperationSchema(schema)
        for (duplicate in listOf("[1,1.0]", "[{\"a\":1,\"b\":2},{\"b\":2.0,\"a\":1.0}]", "[[1,2],[1.0,2]]", "[null,null]", "[\"id\",\"id\"]")) {
            val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(Json.parseToJsonElement(duplicate), schema, "request.ids") }
            assertEquals("request.ids[1]", failure.fieldPath)
        }
        validateOperationSchema(Json.parseToJsonElement("[1,\"1\",true,null,[1,2],[2,1]]"), schema)
        validateOperationSchema(Json.parseToJsonElement("[1,1]"), JsonObject(schema + ("uniqueItems" to JsonPrimitive(false))))
        assertFailsWith<IllegalArgumentException> { checkOperationSchema(JsonObject(schema + ("uniqueItems" to JsonPrimitive("true")))) }
    }

    @Test fun unsupportedConstraintsAndDuplicateDefinitionsCannotBePublished() {
        val unsupported = objectSchema(buildJsonObject { put("field", buildJsonObject { put("type", "string"); put("format", "uri") }) })
        assertFailsWith<IllegalArgumentException> { WorkspaceOperationDefinition("asset_import", "Import", unsupported, WorkspaceOperationKind.DOCUMENT, resultSchema = objectSchema(buildJsonObject {})) }
        val schema = objectSchema(buildJsonObject {})
        val definition = WorkspaceOperationDefinition("project_inspect", "Read state", schema, WorkspaceOperationKind.QUERY, resultSchema = schema)
        val registry = WorkspaceOperationRegistry()
        registry.register(definition) { request, _ -> WorkspaceOperationOutput(request) }
        assertFailsWith<IllegalArgumentException> { registry.register(definition) { request, _ -> WorkspaceOperationOutput(request) } }
        registry.definitions()
        assertFailsWith<IllegalStateException> { registry.register(definition.copy(id = "project_list")) { request, _ -> WorkspaceOperationOutput(request) } }
    }

    @Test fun cancellationIsNeverConvertedToABusinessResult() = runBlocking {
        val registry = WorkspaceOperationRegistry()
        registry.register(WorkspaceOperationDefinition("view_render", "Render", objectSchema(buildJsonObject {}), WorkspaceOperationKind.QUERY, resultSchema = objectSchema(buildJsonObject {}))) { _, _ ->
            throw CancellationException("Cancelled")
        }
        assertFailsWith<CancellationException> { registry.invoke("view_render", buildJsonObject {}, WorkspaceOperationContext(MutationAuthor.AGENT)) }
        Unit
    }
}
