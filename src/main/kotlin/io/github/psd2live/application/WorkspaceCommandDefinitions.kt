package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.project.WorkspaceCreateParameterRequest
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceKeyformChannels
import io.github.psd2live.project.WorkspaceKeyformCopyRequest
import io.github.psd2live.project.WorkspaceKeyformDeleteRequest
import io.github.psd2live.project.WorkspaceKeyformGeometry
import io.github.psd2live.project.WorkspaceKeyformSetRequest
import io.github.psd2live.project.WorkspaceKeyformTargetRef
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspaceLayerSnapshot
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceObjectSnapshot
import io.github.psd2live.project.WorkspaceParameterSnapshot
import io.github.psd2live.project.WorkspacePixelRect
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.github.psd2live.project.WorkspaceRenderedView
import io.github.psd2live.project.WorkspaceRigKPoseRequest
import io.github.psd2live.project.WorkspaceTaskSnapshot
import io.github.psd2live.project.WorkspaceTaskStatus
import io.github.psd2live.project.WorkspaceUpdateParameterRequest
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.project.WorkspaceMutationResult

import io.github.psd2live.core.Bounds
import kotlinx.serialization.json.*
import java.util.Base64

internal fun registerWorkspaceCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
    registerCatalogStateCommands(catalog, workspace)
    registerCatalogParameterCommands(catalog, workspace)
    registerCatalogQueriesCommands(catalog, workspace)
    registerCatalogRigCommands(catalog, workspace)
    registerCatalogOutputCommands(catalog, workspace)
    registerCatalogHistoryCommands(catalog, workspace)
    registerCatalogTaskRecordCommands(catalog, workspace)
    registerCatalogRenderCommands(catalog, workspace)
    registerCatalogPoseCommands(catalog, workspace)
    registerCatalogPhysicsCommands(catalog, workspace)
    registerCatalogSwingCommands(catalog, workspace)
    registerCatalogAssetCommands(catalog, workspace)
    registerCatalogSourceCommands(catalog, workspace)
    registerCatalogQueriesRigCommands(catalog, workspace, workspace)
    registerAssetCommands(catalog, workspace)
    registerSourceCommands(catalog, workspace)
    registerObservationCommands(catalog, workspace)
}

private fun registerCatalogStateCommands(catalog: WorkspaceCommands, state: WorkspaceStatePort) {
	catalog.register(
		name = "project_get_state",
		description = "Read the current PSD2Live project, revision, selection, canvas and summary. Call this before planning work.",
		hints = READ_ONLY,
	) {
		val json = state.snapshot().toJson(includeLayers = false)
		jsonResult(json)
	}

	catalog.register(
		name = "project_list_layers",
		description = "List stable layer IDs, semantic labels, bounds, visibility and deletion state without reading PSD binary data.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("semantic_tag") {
					put("type", "string")
					put("description", "Optional lowercase semantic tag filter, such as front_hair or unknown")
				}
				putJsonObject("include_deleted") {
					put("type", "boolean")
					put("description", "Include soft-deleted layers; defaults to false")
				}
			},
		),
		hints = READ_ONLY,
	) { request ->
		val semanticTag = request.arguments.get("semantic_tag")?.jsonPrimitive?.contentOrNull
		val includeDeleted = request.arguments.get("include_deleted")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
		val snapshot = state.snapshot()
		val layers = snapshot.layers.filter { layer ->
			(includeDeleted || !layer.deleted) && (semanticTag == null || layer.semanticTag == semanticTag.lowercase())
		}
		jsonResult(snapshot.toJson(includeLayers = true, layers = layers))
	}

	catalog.register(
		name = "project_list_parameters",
		description = "List every parameter ID, range, default, current value and kind in the evaluated rig.",
		hints = READ_ONLY,
	) {
		val snapshot = state.snapshot()
		jsonResult(buildJsonObject {
			put("revisionId", snapshot.revisionId)
			put("parameterCount", snapshot.parameters.size)
			put("parameters", JsonArray(snapshot.parameters.map(WorkspaceParameterSnapshot::toJson)))
		})
	}
}

private fun registerCatalogParameterCommands(catalog: WorkspaceCommands, parameter: WorkspaceParameterPort) {
	catalog.register(
		name = "parameter_create",
		description = "Create a real Cubism parameter in the authoritative rig. kind=blend_shape stores an additive blend-shape parameter (square keys, neutral at 0) instead of a keyform axis. It survives layer/mesh rebuilds, history checkout, restart and export.",
		inputSchema = parameterCreateSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.createParameter(
				WorkspaceCreateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedState = request.requiredString("state"),
					name = request.requiredString("name"),
					min = request.float("min", -1f),
					max = request.float("max", 1f),
					default = request.float("default", 0f),
					kind = request.optionalString("kind") ?: "normal",
					repeat = request.boolean("repeat", false),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "parameter_update",
		description = "Update a Cubism parameter's name, numeric range/default, kind, or repeat flag. The stable parameter ID is not renamed.",
		inputSchema = parameterUpdateSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.updateParameter(
				WorkspaceUpdateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedState = request.requiredString("state"),
					name = request.optionalString("name"),
					min = request.optionalFloat("min"),
					max = request.optionalFloat("max"),
					default = request.optionalFloat("default"),
					kind = request.optionalString("kind"),
					repeat = request.optionalBoolean("repeat"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "parameter_delete",
		description = "Delete a Cubism parameter and safely collapse every drawable, deformer, part and glue keyform axis at its prior default. History remains recoverable.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("parameter_id") { put("type", "string") }
				putJsonObject("state") { put("type", "string") }
				putJsonObject("task_id") { put("type", "string") }
			},
			required = listOf("parameter_id", "state"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.deleteParameter(
				parameterId = request.requiredString("parameter_id"),
				expectedState = request.requiredString("state"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}
}

private fun registerCatalogQueriesCommands(catalog: WorkspaceCommands, queries: WorkspaceQueries) {
	catalog.register(
		name = "object_get",
		description = "Read an authoritative rig object (mesh, warp deformer, rotation deformer, part, glue) including its current keyforms, channels, deformer hierarchy, and geometry bounds.",
		inputSchema = objectGetSchema(),
		hints = READ_ONLY,
	) { request ->
		mutationResult {
			queries.getObject(request.targetRef()).toJson()
		}
	}

    catalog.register(name="rig_inspect", description="Budgeted geometry inspection at coordinate: summary (default, representation and native control availability, no points), or points (paged up to 256). Local or evaluated canvas coordinates. Does not dump all keyforms.",
        inputSchema=rigGeometrySchema(false), hints=READ_ONLY) { request ->
        mutationResult { queries.inspectRigGeometry(requireNotNull(request.arguments)) }
    }

	catalog.register(
		name = "history_list",
		description = "Read the append-only branch-preserving workspace history and current HEAD. Nodes cannot be edited or deleted.",
		hints = READ_ONLY,
	) {
		mutationResult { queries.history().toJson() }
	}

    catalog.register(
        name = "rig_preview",
        description = "Evaluate proposed rig_transform operations without editing or advancing history. Returns compact displacement and triangle diagnostics relative to the input pose. Does not judge painted coverage or appearance.",
        inputSchema = WorkspaceCommandSchema(properties = rigGeometrySchema(true).properties,
            required = listOf("target", "coordinate", "operations")), hints = READ_ONLY,
    ) { request -> mutationResult { queries.inspectRigGeometry(request.arguments) } }

    catalog.register(
        name = "rig_list_objects",
        description = "Find meshes, Parts and deformers by name or stable ID. Returns compact names, parent relationships and source layer IDs without geometry. Optional query/kind and pagination keep discovery small.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            for(key in listOf("query","kind")) putJsonObject(key) { put("type","string") }
            putJsonObject("offset") { put("type","integer");put("minimum",0) }
            putJsonObject("limit") { put("type","integer");put("minimum",1);put("maximum",256);put("default",64) }
        }), hints = READ_ONLY,
    ) { request -> mutationResult {
        val query=request.optionalString("query")
        val kind=request.optionalString("kind")
        require(kind==null || kind in setOf("mesh","warp","rotation","part")) { "Unknown kind" }
        val all=queries.listRigObjectSummaries().filter { entry ->
            (kind==null || entry["kind"]?.jsonPrimitive?.content==kind) &&
            (query==null || listOf("id","name","layerId").any { entry[it]?.jsonPrimitive?.contentOrNull?.contains(query,ignoreCase=true)==true })
        }
        val offset=request.arguments.get("offset")?.jsonPrimitive?.int ?: 0
        val limit=request.arguments.get("limit")?.jsonPrimitive?.int ?: 64
        require(offset>=0 && limit in 1..256)
        buildJsonObject { put("objects",JsonArray(all.drop(offset).take(limit)));put("total",all.size)
            if(offset.toLong()+limit<all.size)put("nextOffset",offset+limit) }
    } }

    catalog.register(
        name = "physics_list",
        description = "List every physics group the model exports or could: hair/eye presets, skeleton follow-through, swing pendulums and user groups, each with origin, enabled/active, what replaces it, and why an inactive one does not export.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {}), hints = READ_ONLY,
    ) { mutationResult { buildJsonObject { putJsonArray("groups") { queries.listPhysics().forEach { add(it.toJson()) } } } } }

    catalog.register(
        name = "physics_simulate",
        description = "Run the exported physics (Cubism's evaluation, 60 fps) from rest: inputs jump to their values at t=0, hold for hold seconds, then return to defaults. Per output: start/peak/final and settle time while held and after release, plus sampled [t, value]. Read-only; checks swing size, overshoot and settling that static poses cannot.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("inputs") { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "number") })
                put("description", "Parameter values to step to, e.g. {\"ParamAngleX\": 30}") }
            putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Groups to run; default every active group") }
            putJsonObject("hold") { put("type", "number"); put("minimum", 0); put("maximum", 20) }
            putJsonObject("duration") { put("type", "number"); put("minimum", 0.1); put("maximum", 20) }
            putJsonObject("samples") { put("type", "integer"); put("minimum", 2); put("maximum", 60) }
        }, required = listOf("inputs")), hints = READ_ONLY,
    ) { request -> mutationResult { queries.simulatePhysics(request.arguments) } }

    catalog.register(
        name = "swing_list",
        description = "List regenerating swings: target Warps and, per direction, the driven parameters, shape and pendulum.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {}), hints = READ_ONLY,
    ) { mutationResult { buildJsonObject { putJsonArray("swings") { queries.listSwings().forEach { add(it.toJson()) } } } } }

    catalog.register(
        name = "path_inspect",
        description = "Inspect deform paths by mesh target or path ID, returning point positions in mesh coordinates and barycentric bindings.",
        inputSchema = WorkspaceCommandSchema(
            properties = buildJsonObject {
                putJsonObject("target") { put("type", "string"); put("description", "Optional mesh target, e.g. mesh:hair") }
                putJsonObject("path_id") { put("type", "string"); put("description", "Optional path ID") }
            },
        ),
        hints = READ_ONLY,
    ) { request ->
        val puppet = queries.currentPuppet() ?: error("No model loaded")
        val json = WorkspacePathEdits.inspect(puppet, request.arguments)
        jsonResult(json)
    }

    catalog.register(
        name = "path_preview",
        description = "Preview mesh vertex displacements caused by moving deform path control points without modifying the project.",
        inputSchema = WorkspaceCommandSchema(
            properties = buildJsonObject {
                putJsonObject("target") { put("type", "string"); put("description", "Mesh target, e.g. mesh:hair") }
                putJsonObject("path_id") { put("type", "string"); put("description", "ID of the deform path") }
                putJsonObject("moved_points") { put("type", "array"); put("description", "Array of moved [x, y] coordinates") }
                putJsonObject("width") { put("type", "number"); put("description", "Optional custom influence width to preview deformation with") }
                putJsonObject("hardness") { put("type", "number"); put("description", "Optional custom falloff hardness percent (0..100) to preview deformation with") }
                putJsonObject("show_width") { put("type", "boolean"); put("description", "Whether to draw the influence width boundary circle in preview image (default: true)") }
                putJsonObject("show_hardness") { put("type", "boolean"); put("description", "Whether to draw the core hardness circle in preview image (default: true)") }
                putJsonObject("render") { put("type", "boolean"); put("description", "Whether to render a diagnostic visual preview image (default: true)") }
            },
            required = listOf("target", "path_id", "moved_points"),
        ),
        hints = READ_ONLY,
    ) { request ->
        val puppet = queries.currentPuppet() ?: error("No model loaded")
        val json = WorkspacePathEdits.preview(puppet, request.arguments)
        val previewBase64 = json["previewImage"]?.jsonPrimitive?.contentOrNull
        if (previewBase64 != null) {
            WorkspaceOperationOutput(JsonObject(json - "previewImage"), listOf(Base64.getDecoder().decode(previewBase64)))
        } else {
            jsonResult(json)
        }
    }
}

private fun registerCatalogRigCommands(catalog: WorkspaceCommands, rig: WorkspaceRigPort) {
    catalog.register(name="rig_transform", description="Apply 1..32 ordered translate/scale/rotate/bend/curve/smooth operations to one Warp or mesh at an exact coordinate. Server edits all points in one history commit. Shared selection + range + ordered operations avoid transferring dense geometry. Supports index, rectangle, point-radius and line-radius selections. Includes root-anchored sway. rig_inspect provides coordinate and axis information.",
        inputSchema=rigGeometrySchema(true), hints=MUTATING) { request ->
        mutationResult { rig.transformRigGeometry(requireNotNull(request.arguments)).toJson() }
    }

	catalog.register(
		name = "keyform_set",
		description = "Set or update keyform geometry and/or channels on a target at an exact parameter coordinate. A blend_shape parameter in the coordinate is captured as an additive blend key (value 0 is the neutral base and is not stored); geometry is then the full local shape at that pose, and normal parameters in the same coordinate are only the viewing pose.",
		inputSchema = keyformSetSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			rig.setKeyform(
				WorkspaceKeyformSetRequest(
					expectedState = request.requiredString("state"),
					target = request.targetRef(),
					coordinate = request.coordinateMap(),
					geometry = request.optionalGeometry(),
					channels = request.optionalChannels(),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "keyform_delete",
		description = "Delete a keyform key or an entire parameter axis from a target's geometry grid or specific channel track.",
		inputSchema = keyformDeleteSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			rig.deleteKeyform(
				WorkspaceKeyformDeleteRequest(
					expectedState = request.requiredString("state"),
					target = request.targetRef(),
					parameterId = request.requiredString("parameter_id"),
					keyValue = request.optionalFloat("key_value"),
					channel = request.optionalString("channel"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "keyform_copy",
		description = "Copy keyform geometry and/or channels from a source parameter coordinate to a destination parameter coordinate (on the same or another target).",
		inputSchema = keyformCopySchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			rig.copyKeyform(
				WorkspaceKeyformCopyRequest(
					expectedState = request.requiredString("state"),
					sourceTarget = request.targetRef("source_target"),
					sourceCoordinate = request.coordinateMap("source_coordinate"),
					destinationTarget = if (request.hasTarget("destination_target")) request.targetRef("destination_target") else null,
					destinationCoordinate = request.coordinateMap("destination_coordinate"),
					channels = request.optionalStringList("channels"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "rig_k_pose",
		description = "Capture an explicit or current parameter pose deformation onto a target as keyform keys across all specified parameters.",
		inputSchema = rigKPoseSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			val params = if (request.arguments.containsKey("parameters") == true) {
				request.floatMap("parameters")
			} else {
				request.coordinateMap("coordinate")
			}
			rig.rigKPose(
				WorkspaceRigKPoseRequest(
					expectedState = request.requiredString("state"),
					target = request.targetRef(),
					parameters = params,
					geometry = request.optionalGeometry(),
					channels = request.optionalChannels(),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

    catalog.register(
        name = "object_edit",
        description = "Rename, show/hide, organize or rebind objects. Applies 1..128 ordered edits atomically in one recoverable history commit. Stable IDs are unchanged. Organizational moves and deformation bindings are separate operations.",
        inputSchema = objectEditSchema(), hints = MUTATING,
    ) { request -> mutationResult { rig.editObjects(request.arguments).toJson() } }

    catalog.register(
        name = "warp_create",
        description = "Create an independent identity Warp for one or more existing meshes under their common Warp parent. Preserves mesh pixels, keyforms, masks and inherited motion. The new lattice uses parent-normalized 0..1 coordinates across the parent frame; requested rows/columns are minima, rounded up together to align parent knots and preserve inherited motion. Inspect actual dimensions with object_get. Use rig_list_objects and object_get first; animate with keyform_set. No special hair-split API is required.",
        inputSchema = rigObjectCreateSchema(), hints = MUTATING,
    ) { request -> mutationResult {
        rig.createWarp(io.github.psd2live.core.RigWarpEdit.fromJson(request.arguments),
            request.requiredString("state"), request.optionalString("task_id")).toJson()
    } }

    catalog.register(
        name = "path_delete",
        description = "Delete a deform path by ID.",
        inputSchema = WorkspaceCommandSchema(
            properties = buildJsonObject {
                putJsonObject("state") { put("type", "string"); put("description", "Optimistic concurrency state") }
                putJsonObject("path_id") { put("type", "string"); put("description", "ID of the deform path to delete") }
            },
            required = listOf("state", "path_id"),
        ),
        hints = MUTATING,
    ) { request ->
        mutationResult {
            val args = request.arguments
            val (pathId, command) = WorkspacePathEdits.createDeleteCommand(args)
            val state = request.requiredString("state")
            val res = rig.authorRig(state, buildJsonArray { add(command) }, MutationAuthor.AGENT)
            buildJsonObject {
                put("historyNodeId", res.historyNodeId)
                put("state", requireNotNull(res.state)); put("project_id", requireNotNull(res.projectId))
                put("revisionId", res.revisionId)
                put("deletedPathId", pathId)
                put("summary", res.summary)
            }
        }
    }

    catalog.register(
        name = "path_deform",
        description = "Deform an ArtMesh by moving deform path control points and baking the result as a keyform at the specified parameter key.",
        inputSchema = WorkspaceCommandSchema(
            properties = buildJsonObject {
                putJsonObject("state") { put("type", "string"); put("description", "Optimistic concurrency state") }
                putJsonObject("target") { put("type", "string"); put("description", "Mesh target, e.g. mesh:hair") }
                putJsonObject("path_id") { put("type", "string"); put("description", "ID of the deform path") }
                putJsonObject("key") { put("type", "object"); put("description", "Destination parameter key coordinate") }
                putJsonObject("moved_points") { put("type", "array"); put("description", "Array of moved [x, y] coordinates matching path points") }
            },
            required = listOf("state", "target", "path_id", "key", "moved_points"),
        ),
        hints = MUTATING,
    ) { request ->
        mutationResult {
            val args = request.arguments
            val command = WorkspacePathEdits.createDeformCommand(args)
            val state = request.requiredString("state")
            val res = rig.authorRig(state, buildJsonArray { add(command) }, MutationAuthor.AGENT)
            buildJsonObject {
                put("historyNodeId", res.historyNodeId)
                put("state", requireNotNull(res.state)); put("project_id", requireNotNull(res.projectId))
                put("revisionId", res.revisionId)
                put("target", command.getValue("target"))
                put("key", command.getValue("key"))
                put("summary", res.summary)
            }
        }
    }
}

private fun registerCatalogOutputCommands(catalog: WorkspaceCommands, output: WorkspaceOutputPort) {
    catalog.register(
        name = "project_save",
        hints = MUTATING,
        description = "Save the complete portable project to its selected file, creating an immediate history checkpoint. Choose the file location in the UI first.",
    ) { mutationResult { output.saveProject().toJson() } }
}

private fun registerCatalogHistoryCommands(catalog: WorkspaceCommands, history: WorkspaceHistoryPort) {
    catalog.register(
        name = "history_checkpoint",
        hints = MUTATING,
        description = "Append an explicit history checkpoint even when the model is unchanged.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject { putJsonObject("summary") { put("type", "string") } }, required = listOf("summary")),
    ) { request -> mutationResult { history.checkpoint(request.requiredString("summary")).toJson() } }

	catalog.register(
		name = "history_checkout",
		description = "Move workspace HEAD to any immutable history node and rebuild that exact editable source/rig state. Old branches remain available.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("node_id") { put("type", "string"); put("description", "History node to restore") }
			},
			required = listOf("node_id"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult { history.checkoutHistory(request.requiredString("node_id"), MutationAuthor.AGENT).toJson() }
	}
}

private fun registerCatalogTaskRecordCommands(catalog: WorkspaceCommands, taskRecord: WorkspaceTaskRecordPort) {
	catalog.register(
		name = "task_start",
		description = "Create a resumable in-app checkpoint record from the Agent's own dynamic plan. This does not prescribe or approve the workflow.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("objective") { put("type", "string") }
				putJsonObject("plan") {
					put("type", "array")
					putJsonObject("items") { put("type", "string") }
				}
			},
			required = listOf("objective", "plan"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult {
			taskRecord.startTask(request.requiredString("objective"), request.stringList("plan")).toJson()
		}
	}

	catalog.register(
		name = "task_update",
		description = "Append a progress/checkpoint event to a long Agent task, including artifact/view/asset/history references.",
		inputSchema = taskUpdateSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			val status = runCatching { WorkspaceTaskStatus.valueOf(request.requiredString("status").uppercase()) }
				.getOrElse { throw IllegalArgumentException("Unknown task status") }
			taskRecord.updateTask(
				taskId = request.requiredString("task_id"),
				status = status,
				plan = request.optionalStringList("plan"),
				currentStep = request.optionalInteger("current_step"),
				progress = request.optionalFloat("progress"),
				message = request.optionalString("message").orEmpty(),
				artifactIds = request.stringList("artifact_ids", required = false),
			).toJson()
		}
	}

	catalog.register(
		name = "task_get",
		description = "Read one long task with its Agent-authored plan, status, progress, artifacts and append-only event log.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject { putJsonObject("task_id") { put("type", "string") } },
			required = listOf("task_id"),
		),
		hints = READ_ONLY,
	) { request -> mutationResult { taskRecord.task(request.requiredString("task_id")).toJson() } }

	catalog.register(
		name = "task_list",
		description = "List long tasks persisted for the currently loaded PSD version.",
		hints = READ_ONLY,
	) {
		jsonResult(buildJsonObject {
			putJsonArray("tasks") { taskRecord.tasks().forEach { add(it.toJson(includeEvents = false)) } }
		})
	}
}

private fun registerCatalogRenderCommands(catalog: WorkspaceCommands, render: WorkspaceRenderPort) {
	catalog.register(
		name = "view_render_layer",
		description = "Render one layer directly from RGBA model data as transparent or checkerboard PNG. This is not a UI screenshot. For differences, separation, or missing-pixel completion, pass this View to Nano Banana Pro/NBP or GPT Image 2; never redraw it with Python/PIL/OpenCV.",
		inputSchema = viewSchema(includeBackground = true),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			val layerId = request.requiredString("layer_id")
			val background = request.arguments.get("background")?.jsonPrimitive?.contentOrNull
				?.uppercase()?.let(WorkspaceViewBackground::valueOf) ?: WorkspaceViewBackground.TRANSPARENT
			render.captureObservation().renderLayer(layerId, background, request.outputSpec())
		}
	}

	catalog.register(
		name = "view_render_context",
		description = "Composite visible layers into one focused PNG around a layer. object_scale below 1 includes surrounding context; this never returns a PSD or UI screenshot. Use it as a reference for Nano Banana Pro/NBP or GPT Image 2 when painted pixels must be changed or reconstructed.",
		inputSchema = viewSchema(includeBackground = true, includeFocus = true),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			render.captureObservation().renderContext(
				layerId = request.requiredString("layer_id"),
				objectScale = request.float("object_scale", 0.65f),
				aspectRatio = request.float("aspect_ratio", 1f),
				background = request.background(),
				output = request.outputSpec(),
			)
		}
	}

	catalog.register(
		name = "view_render_model",
		description = "Evaluate a rig pose, composite selected layers into one PNG, and render only an explicit canvas rectangle or a focused object region. Returns reversible pixel-to-canvas placement metadata.",
		inputSchema = modelViewSchema(),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			val annotatePathWidth = request.arguments.get("annotate_path_width")?.jsonPrimitive?.content == "true"
			val annotatePathHardness = request.arguments.get("annotate_path_hardness")?.jsonPrimitive?.content == "true"
			val annotatePathRadius = request.arguments.get("annotate_path_radius")?.jsonPrimitive?.content == "true"
			render.captureObservation().renderModel(
				WorkspaceModelViewRequest(
					parameters = request.floatMap("parameters"),
					includeLayerIds = request.optionalStringSet("include_layer_ids"),
					annotateLayerIds = request.optionalStringSet("annotate_layer_ids").orEmpty(),
					annotateDeformerIds = request.optionalStringSet("annotate_deformer_ids").orEmpty(),
					annotatePathIds = request.optionalStringSet("annotate_path_ids").orEmpty(),
					annotatePathWidth = annotatePathWidth || annotatePathRadius,
					annotatePathHardness = annotatePathHardness || annotatePathRadius,
					annotatePathRadius = annotatePathRadius,
					pointIndices = request.arguments.get("point_indices")?.jsonPrimitive?.content == "true",
					frame = request.viewFrame(),
					background = request.background(),
					output = request.outputSpec(),
				),
			)
		}
	}

    catalog.register(
        name = "view_check_coverage",
        description = "Measure whether selected layers cover an explicitly expected canvas rectangle at one pose. For scalp gaps select hair layers, not the opaque face underneath. Returns uncovered pixel bounds and a mapped image; the region's semantic expectation is caller-supplied.",
        inputSchema = WorkspaceCommandSchema(properties = JsonObject(requireNotNull(modelViewSchema().properties) + buildJsonObject {
            putJsonObject("alpha_threshold") { put("type","integer"); put("minimum",1); put("maximum",255); put("default",128) }
        }), required = listOf("viewport", "include_layer_ids")), hints = READ_ONLY,
    ) { request ->
        try {
            val frame=request.viewFrame()
            require(frame is WorkspaceViewFrame.CanvasRect) { "Use canvas_rect for the expected coverage region" }
            val view=render.captureObservation().renderModel(WorkspaceModelViewRequest(parameters=request.floatMap("parameters"),
                includeLayerIds=request.optionalStringSet("include_layer_ids"), frame=frame,
                background=WorkspaceViewBackground.TRANSPARENT, output=request.outputSpec()))
            val image=javax.imageio.ImageIO.read(view.png.inputStream()) ?: error("Invalid rendered PNG")
            val measurement=measureCoverage(image,request.arguments.get("alpha_threshold")?.jsonPrimitive?.int ?: 128)
            val metadata=buildJsonObject { put("view",view.toJson()); put("coverage",measurement) }
            WorkspaceOperationOutput(metadata, listOf(view.png))
        } catch(e: IllegalArgumentException) { throw e }
          catch(e: IllegalStateException) { throw e }
    }
}

private fun registerCatalogPoseCommands(catalog: WorkspaceCommands, render: WorkspaceRenderPort) {
    catalog.register(
        name = "view_render_poses",
        description = "Compare 1..9 poses in one labeled sheet, in input order. parameters are shared; poses override them. target_long_edge/max_bytes bound the entire sheet. Each tile imageRect [x,y,width,height] maps to the shared canvasRect [left,top,right,bottom]; labels are excluded. Static poses, not physics or revision comparison.",
        inputSchema = WorkspaceCommandSchema(properties = JsonObject(requireNotNull(modelViewSchema().properties) + buildJsonObject {
            putJsonObject("poses") { put("type","array"); put("minItems",1); put("maxItems",9)
                putJsonObject("items") { put("type","object"); putJsonObject("additionalProperties") { put("type","number") } }
            }
            putJsonObject("columns") { put("type","integer"); put("minimum",1); put("maximum",3); put("description","Row-major columns; omit for a compact grid") }
        }), required = listOf("viewport", "poses")), hints = READ_ONLY,
    ) { request ->
        try {
            val poses = request.arguments.getValue("poses").jsonArray
            require(poses.size in 1..9)
            val columns = request.arguments.get("columns")?.jsonPrimitive?.int ?: poseSheetColumns(poses.size)
            require(columns in 1..minOf(3, poses.size)) { "columns must be 1..min(3, pose count)" }
            val sharedParameters = request.arguments.get("parameters")?.jsonObject?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
            val output = request.outputSpec()
            val rows = (poses.size + columns - 1) / columns
            val tileOutput = output.copy(targetLongEdge = maxOf(128, output.targetLongEdge / maxOf(columns, rows)))
            val frame=request.viewFrame()
            require(frame is WorkspaceViewFrame.CanvasRect) { "Use canvas_rect for a fixed comparison camera" }
            val observation = render.captureObservation()
            val revision = observation.snapshot().revisionId
            val pathWidth = request.boolean("annotate_path_width", false)
            val pathHardness = request.boolean("annotate_path_hardness", false)
            val pathRadius = request.boolean("annotate_path_radius", false)
            val views=poses.map { pose -> observation.renderModel(WorkspaceModelViewRequest(
                parameters=sharedParameters + pose.jsonObject.mapValues { it.value.jsonPrimitive.float },
                includeLayerIds=request.optionalStringSet("include_layer_ids"), frame=frame,
                background=request.background(), output=tileOutput,
                annotateLayerIds=request.optionalStringSet("annotate_layer_ids").orEmpty(),
                annotateDeformerIds=request.optionalStringSet("annotate_deformer_ids").orEmpty(),
                annotatePathIds=request.optionalStringSet("annotate_path_ids").orEmpty(),
                annotatePathWidth=pathWidth || pathRadius,
                annotatePathHardness=pathHardness || pathRadius,
                annotatePathRadius=pathRadius,
                pointIndices=request.boolean("point_indices",false))) }
            check(views.all { it.revisionId == revision }) { "Observation returned another revision" }
            val sheet = renderPoseSheet(views, output, columns)
            WorkspaceOperationOutput(sheet.metadata, sheet.images)
        } catch(e: IllegalArgumentException) { throw e }
          catch(e: IllegalStateException) { throw e }
    }
}

private fun registerCatalogPhysicsCommands(catalog: WorkspaceCommands, physics: WorkspacePhysicsPort) {
    catalog.register(
        name = "physics_put",
        description = "Create or change a physics group by ID; only given fields change. An existing ID (generated or user) is the base, a new ID starts as a 10-unit hair pendulum fed by head/body X and Z. " +
            "inputs/outputs/segments replace their lists; length/mobility/delay/acceleration set every segment (length is the whole strand) and output_scale every output. " +
            "A pendulum has 1..16 segments; output vertex k reads the tip of segment k as an angle relative to segment k-1 (vertex 1: to gravity). " +
            "Editing a generated group replaces it until physics_delete; a user group on a generated group's output replaces that group. enabled=false turns any group off. " +
            "Parameters must exist and the outputs need authored forms; physics drives parameters, not Warps. Enables physics in the same commit unless only turning a group off.",
        inputSchema = physicsPutSchema(), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        physics.putPhysics(arguments, request.requiredString("state"), request.optionalString("task_id")).toJson()
    } }

    catalog.register(
        name = "physics_delete",
        description = "Delete a user physics group, or return a replaced generated group to its generated values. Generated groups themselves are turned off with physics_put enabled=false.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        physics.deletePhysics(request.requiredString("id"), request.requiredString("state")).toJson()
    } }

    catalog.register(
        name = "physics_config",
        description = "Set the physics evaluation order and/or rate. Cubism runs groups in order and a later group reads an earlier group's outputs in the same step; order lists group IDs to run first, the rest follow in their current order. fps is the project's one frame rate: the preview and physics step at it and physics3.json and the CMO3 declare it (default 60; 0 is unlimited: the preview follows the display and no Fps is declared).",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("order") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            putJsonObject("fps") { put("type", "integer"); put("minimum", 0); put("maximum", 240) }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("state")), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        physics.configurePhysics(arguments["order"]?.jsonArray?.map { it.jsonPrimitive.content }, arguments["fps"]?.jsonPrimitive?.intOrNull,
            request.requiredString("state")).toJson()
    } }

    catalog.register(
        name = "physics_import",
        description = "Import a Cubism physics3.json (absolute path) as user groups: an existing ID is replaced (a generated group until physics_delete), imported groups run after the current ones in file order, other user groups on the same outputs are turned off, and the file's Fps becomes the project's. Reports missing parameters; create them or edit the groups.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("path") { put("type", "string") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("path", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        val (result, report) = physics.importPhysics(request.requiredString("path"), request.requiredString("state"))
        kotlinx.serialization.json.JsonObject(result.toJson() + report)
    } }

    catalog.register(
        name = "physics_fit",
        description = "Scale a group's outputs so a standard head sway (the panel's response curve: pulled fully right for 1 s, then let go) swings each output to target percent of its parameter's end (default 100). Outputs that do not move keep their scale. " +
            "observed_peaks fits to a measured response instead of the standard sway: keys are output indexes (\"0\", \"1\"...), each mapped to the reach physics_audition reported as peaks (1 = the parameter's end), as the panel's Fit after dragging the pendulum. At least one output must have moved.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("target") { put("type", "number"); put("minimum", 10); put("maximum", 300) }
            putJsonObject("observed_peaks") {
                put("type", "object"); put("minProperties", 1)
                putJsonObject("additionalProperties") { put("type", "number"); put("minimum", 0) }
            }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        val target = (request.arguments.get("target")?.jsonPrimitive?.floatOrNull ?: 100f) / 100f
        val observed = request.arguments["observed_peaks"]?.let(WorkspacePhysicsEdits::observedPeaks)
        physics.fitPhysics(request.requiredString("id"), target, request.requiredString("state"), observed).toJson()
    } }
}

private fun registerCatalogSwingCommands(catalog: WorkspaceCommands, swing: WorkspaceSwingPort) {
    catalog.register(
        name = "swing_put",
        description = "Create or replace a regenerating swing by ID. Each target Warp gets a -1/0/1 axis per parameter, recomputed from its current forms whenever the rig rebuilds; mesh targets are wrapped in a tight Warp in the same commit. " +
            "kind=lateral swings the tip left/right, kind=vertical up/down: a bend across the pinned edge, or a stretch along it (vertical under a top pivot bounces). Pass motions=[{kind:lateral,...},{kind:vertical,...}] to combine both on the same Warp; each direction has its own fields, parameters and pendulum. " +
            "parallel (0..1) keeps the tip edge level while bending, so every column sways alongside the others: use it high for hair whose several strands share one Warp. " +
            "Missing parameters are created (-1..1) as ParamSwing<id>[_X|_Y][_<segment>]. Omit physics to size the pendulums from the target, physics_enabled=false for none; lateral pendulums take head/body X and Z, vertical ones head/body pitch. Segments (1..3) share a direction's pendulum with a vertex per segment. Rotation deformers are not targets.",
        inputSchema = swingSchema(), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        val parsed = io.github.psd2live.core.SwingAuthoring.request(arguments)
        swing.putSwing(parsed.edit, parsed.estimatePhysics, request.requiredString("state"), request.optionalString("task_id")).toJson()
    } }

    catalog.register(
        name = "swing_delete",
        description = "Delete a swing by ID. bake=true first writes its current forms as ordinary keys and keeps its pendulum, so the motion can be edited by hand; otherwise its generated axes and created parameters go with it.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("bake") { put("type", "boolean") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        swing.deleteSwing(request.requiredString("id"), request.arguments.get("bake")?.jsonPrimitive?.booleanOrNull ?: false,
            request.requiredString("state")).toJson()
    } }
}

private fun registerCatalogAssetCommands(catalog: WorkspaceCommands, asset: WorkspaceAssetPort) {
    catalog.register(
        name = "asset_inspect",
        description = "Inspect actual staged PNG pixels, spatial placement and transparency counts. Use for quick usability/alpha diagnosis, then trial assembly. Overlapping hair, minor tone differences and hidden-root/edge variation are not automatic rejection reasons; judge depth, seams and intended motion in composition.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject { putJsonObject("asset_id") { put("type", "string") } }, required = listOf("asset_id")),
        hints = READ_ONLY,
    ) { request ->
        try {
            val preview = asset.inspectAsset(request.requiredString("asset_id"))
            val metadata = buildJsonObject {
                put("asset", preview.asset.toJson()); put("transparentPixels", preview.transparentPixels); put("translucentPixels", preview.translucentPixels)
                put("image_order", "processed, original (when retained)")
            }
            WorkspaceOperationOutput(metadata, listOf(preview.png) + listOfNotNull(preview.originalPng))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw failure
        }
    }

    catalog.register(
        name = "asset_import_png",
        description = "Stage a PNG from original pixels, painting or image generation against the captured workspace state. Omit solid_background for native alpha; reference_id imports need explicit registration. Returns the committed auxiliary state without adding Rig history.",
        inputSchema = pngImportSchema(), hints = MUTATING,
    ) { request ->
        val result = asset.importPng(request.arguments)
        WorkspaceOperationOutput(result.metadata, result.images)
    }

	catalog.register(
		name = "layer_add_from_asset",
		description = "Add a staged PNG as a real editable source layer, generate its mesh/rig through the normal pipeline, and append one immutable history node. No approval step is required.",
		inputSchema = addLayerSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			asset.addLayer(
				WorkspaceAddLayerRequest(
					assetId = request.requiredString("asset_id"),
					expectedState = request.requiredString("state"),
					name = request.requiredString("name"),
					layerId = request.optionalString("layer_id"),
					groupPath = request.optionalString("group_path").orEmpty(),
					insertion = request.layerInsertion(),
					semanticTag = request.optionalString("semantic_tag") ?: "unknown",
					side = request.optionalString("side") ?: "none",
					visible = request.boolean("visible", true),
					opacity = request.float("opacity", 1f),
					trimTransparent = request.boolean("trim_transparent", true),
                    registrationId = request.optionalString("registration_id"),
					parentDeformerId = request.optionalString("parent_deformer_id"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}
}

private fun registerCatalogSourceCommands(catalog: WorkspaceCommands, source: WorkspaceSourcePort) {
	catalog.register(
		name = "layer_soft_delete",
		description = "Remove a layer from the active model without erasing its pixels, authoring or history. Restore it with layer_restore or history_checkout.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("layer_id") { put("type", "string"); put("description", "Stable layer ID to remove from the active workspace") }
				putJsonObject("state") { put("type", "string"); put("description", "Opaque state from workspace_inspect or the preceding result") }
				putJsonObject("task_id") { put("type", "string"); put("description", "Optional long-task correlation ID") }
			},
			required = listOf("layer_id", "state"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult {
			source.softDeleteLayer(
				layerId = request.requiredString("layer_id"),
				expectedState = request.requiredString("state"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}
    catalog.register(
        name = "layer_restore",
        description = "Restore deleted layers with their saved pixels and authoring. Omit layer_ids to restore all deleted layers; restoring active layers is a no-op.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("state") { put("type", "string") }
            putJsonObject("layer_ids") {
                put("type", "array"); put("minItems", 1); put("maxItems", 256)
                putJsonObject("items") { put("type", "string"); put("minLength", 1) }
            }
        }, required = listOf("state")), hints = MUTATING,
    ) { request -> mutationResult {
        source.restoreDeletedLayers(request.arguments["layer_ids"]?.jsonArray?.map { it.jsonPrimitive.content },
            request.requiredString("state")).toJson()
    } }
}

private fun registerCatalogQueriesRigCommands(catalog: WorkspaceCommands, queries: WorkspaceQueries, rig: WorkspaceRigPort) {
    catalog.register(
        name = "path_put",
        description = "Create or update a deform path on an ArtMesh. Points can be local [x, y] coordinates (automatically bound to triangles) or barycentric objects.",
        inputSchema = WorkspaceCommandSchema(
            properties = buildJsonObject {
                putJsonObject("state") { put("type", "string"); put("description", "Optimistic concurrency state") }
                putJsonObject("target") { put("type", "string"); put("description", "Mesh target, e.g. mesh:hair") }
                putJsonObject("id") { put("type", "string"); put("description", "Optional path ID (auto-generated if omitted)") }
                putJsonObject("points") { put("type", "array"); put("description", "Array of points: [[x,y],...] or [{x, y, corner},...]") }
                putJsonObject("width") { put("type", "number"); put("description", "Cubism influence radius in canvas pixels (default 50)") }
                putJsonObject("hardness") { put("type", "number"); put("description", "Cubism hardness percent 0..100 (default 50)") }
                putJsonObject("closed") { put("type", "boolean"); put("description", "Whether path is closed loop (default false)") }
                putJsonObject("level") { put("type", "integer"); put("description", "Edit level 2 or 3 (default 2)") }
            },
            required = listOf("state", "target", "points"),
        ),
        hints = MUTATING,
    ) { request ->
        mutationResult {
            val args = request.arguments
            val puppet = queries.currentPuppet() ?: error("No model loaded")
            val (pathId, command) = WorkspacePathEdits.createPutCommand(puppet, args)
            val state = request.requiredString("state")
            val res = rig.authorRig(state, buildJsonArray { add(command) }, MutationAuthor.AGENT)
            buildJsonObject {
                put("historyNodeId", res.historyNodeId)
                put("state", requireNotNull(res.state)); put("project_id", requireNotNull(res.projectId))
                put("revisionId", res.revisionId)
                put("pathId", pathId)
                put("target", command.getValue("target"))
                put("summary", res.summary)
            }
        }
    }
}

private fun rigObjectCreateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
    properties = buildJsonObject {
        listOf("id", "name", "state", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        putJsonObject("parent_id") { put("type", "string"); put("description", "Existing common Warp parent from object_get") }
        putJsonObject("mesh_ids") { put("type", "array"); put("minItems", 1); put("uniqueItems", true); putJsonObject("items") { put("type", "string") } }
        putJsonObject("fit_local") { put("type", "boolean") }
        listOf("rows", "columns").forEach { key -> putJsonObject(key) { put("type", "integer"); put("minimum", 1); put("maximum", 32) } }
    },
    required = listOf("id", "name", "state", "parent_id", "mesh_ids"),
)

private fun physicsPutSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
    properties = buildJsonObject {
        listOf("id", "name", "state", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        putJsonObject("enabled") { put("type", "boolean") }
        fun JsonObjectBuilder.type() = putJsonObject("type") { put("type", "string"); putJsonArray("enum") { add("x"); add("angle") }
            put("description", "x: sideways travel; angle: tilt") }
        putJsonObject("inputs") { put("type", "array"); put("maxItems", 16)
            put("description", "What moves the root. Weight is % of the normalized range")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("parameter") { put("type", "string") }
                putJsonObject("weight") { put("type", "number"); put("minimum", 0); put("maximum", 100) }
                type(); putJsonObject("reflect") { put("type", "boolean") }
            }; putJsonArray("required") { add("parameter") } } }
        putJsonObject("outputs") { put("type", "array"); put("maxItems", 16)
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("parameter") { put("type", "string") }
                putJsonObject("vertex") { put("type", "integer"); put("minimum", 1); put("maximum", 16) }
                putJsonObject("scale") { put("type", "number"); put("description", "Parameter units per radian of the segment's swing") }
                putJsonObject("weight") { put("type", "number"); put("minimum", 0); put("maximum", 100) }
                putJsonObject("reflect") { put("type", "boolean") }
            }; putJsonArray("required") { add("parameter") } } }
        putJsonObject("segments") { put("type", "array"); put("minItems", 1); put("maxItems", 16)
            put("description", "Root to tip; omitted fields keep the current segment's")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("length") { put("type", "number"); put("exclusiveMinimum", 0) }
                putJsonObject("mobility") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("description", "Shakiness") }
                putJsonObject("delay") { put("type", "number"); put("exclusiveMinimum", 0); put("description", "Reaction speed; higher reacts faster") }
                putJsonObject("acceleration") { put("type", "number"); put("minimum", 0); put("description", "Settling speed") }
            } } }
        putJsonObject("segment_count") { put("type", "integer"); put("minimum", 1); put("maximum", 16); put("description", "Resize the strand, copying the last segment") }
        putJsonObject("length") { put("type", "number"); put("exclusiveMinimum", 0) }
        putJsonObject("mobility") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
        putJsonObject("delay") { put("type", "number"); put("exclusiveMinimum", 0) }
        putJsonObject("acceleration") { put("type", "number"); put("minimum", 0) }
        putJsonObject("output_scale") { put("type", "number") }
        putJsonObject("normalization") { put("type", "object"); put("description", "Span a full-range input maps to")
            putJsonObject("properties") { listOf("position", "angle").forEach { key -> putJsonObject(key) { put("type", "object")
                putJsonObject("properties") { listOf("min", "default", "max").forEach { putJsonObject(it) { put("type", "number") } } } } } } }
    },
    required = listOf("id", "state"),
)

private fun swingSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
    properties = buildJsonObject {
        listOf("id", "name", "state", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        putJsonObject("targets") { put("type", "array"); put("minItems", 1); putJsonObject("items") { put("type", "string") }
            put("description", "Warp or mesh IDs; meshes sharing a parent are wrapped in one Warp") }
        fun JsonObjectBuilder.shapeFields() {
            putJsonObject("magnitude") { put("type", "number"); put("minimum", 0); put("maximum", 0.7); put("description", "Tip travel at ±1 as a fraction of the pinned-edge-to-tip length") }
            putJsonObject("lift") { put("type", "number"); put("minimum", -0.5); put("maximum", 0.5); put("description", "Extra rise (+) or droop (-) of a bending tip at ±1") }
            putJsonObject("softness") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("description", "0 bends evenly; 1 keeps the root stiff") }
            putJsonObject("zoom") { put("type", "number"); put("minimum", -0.5); put("maximum", 0.5); put("description", "Tip width change at ±1") }
            putJsonObject("parallel") { put("type", "number"); put("minimum", 0); put("maximum", 1)
                put("description", "0 swings the lattice like one board (tip edge tilts); 1 keeps the tip edge level, columns swaying side by side like separate strands") }
            putJsonObject("flip") { put("type", "boolean") }
            putJsonObject("physics") { put("type", "object"); put("description", "Pendulum override; omit to size it from the target")
                putJsonObject("properties") { listOf("length", "mobility", "delay", "acceleration", "output_scale").forEach { key -> putJsonObject(key) { put("type", "number") } } } }
        }
        fun JsonObjectBuilder.motionFields() {
            putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("lateral"); add("vertical") } }
            putJsonObject("segments") { put("type", "integer"); put("minimum", 1); put("maximum", 3) }
            putJsonObject("parameters") { put("type", "array"); put("minItems", 1); put("maxItems", 3); putJsonObject("items") { put("type", "string") }
                put("description", "One per segment, root first") }
            shapeFields()
        }
        motionFields()
        putJsonObject("motions") { put("type", "array"); put("minItems", 1); put("maxItems", 2)
            put("description", "One entry per direction; replaces the flat kind/segments/shape fields")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") { motionFields() } } }
        putJsonObject("fulcrum") { put("type", "string"); putJsonArray("enum") { listOf("auto", "top", "bottom", "left", "right").forEach { add(it) } }
            put("description", "Pinned lattice edge; auto hangs tall targets from the top and pivots wide ones on the side nearer the body center") }
        putJsonObject("tilt") { put("type", "number"); put("minimum", -75); put("maximum", 75)
            put("description", "Degrees the swing rectangle turns about the pinned edge's midpoint, for art hanging at a slant; + turns the tip toward the right end of a top/bottom edge or the bottom end of a side edge") }
        putJsonObject("offset_along") { put("type", "number"); put("minimum", -1); put("maximum", 1)
            put("description", "Moves the swing rectangle's pinned edge toward the tip, in pinned-edge-to-tip lengths; what lies behind it stays put") }
        putJsonObject("offset_across") { put("type", "number"); put("minimum", -1); put("maximum", 1)
            put("description", "Moves the swing rectangle along its pinned edge, in edge widths; + toward the right end of a top/bottom edge or the bottom end of a side edge") }
        putJsonObject("preset") { put("type", "string"); putJsonArray("enum") { listOf("hair", "accessory", "cloth").forEach { add(it) } } }
        putJsonObject("physics_enabled") { put("type", "boolean"); put("description", "Default true: generate the pendulums") }
    },
    required = listOf("id", "targets", "state"),
)

private fun pngImportSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
        putJsonObject("state") { put("type", "string") }
        putJsonObject("png_path") { put("type", "string"); put("description", "Absolute local PNG path from the image generator; avoids transferring base64 through model context") }
        putJsonObject("reference_id") { put("type", "string"); put("description", "Reference package from asset_prepare_reference. V2 import keeps raw PNG, removes declared matte, and requires asset_register before adding a layer. Replaces spatial_reference_id.") }
        put("processing", processingSchema())
        putJsonObject("solid_background") { put("type", "string"); put("pattern", "^#[0-9a-fA-F]{6}$"); put("description", "Actual generated matte color. Default generation to pure white #FFFFFF for dark hair or pure black #000000 for light hair to avoid colored fringe; do not guess or automatically strip alpha when omitted. Removes only border-connected near-color pixels, not a baked checkerboard. Inspect remaining matte in composition.") }
        putJsonObject("background_tolerance") { put("type", "integer"); put("minimum", 0); put("maximum", 64); put("default", 16) }
        putJsonObject("require_transparency") { put("type", "boolean"); put("description", "Set true for separated hair: reject fully opaque or empty results after cleanup.") }

		putJsonObject("png_base64") {
			put("type", "string")
			put("description", "PNG bytes encoded as Base64, optionally as a data:image/png;base64 URI")
		}
		putJsonObject("spatial_reference_id") {
			put("type", "string")
			put("description", "spatialReferenceId returned by a View tool in this workspace session")
		}
		putJsonObject("source_pixel_rect") {
			put("type", "object")
			put("description", "Optional crop rectangle in the referenced View's pixel coordinates")
			putJsonObject("properties") {
				listOf("left", "top", "width", "height").forEach { name ->
					putJsonObject(name) { put("type", "integer") }
				}
			}
			putJsonArray("required") {
				listOf("left", "top", "width", "height").forEach { add(JsonPrimitive(it)) }
			}
		}
	},
	required = listOf("state"),
)

private fun parameterCreateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = parameterProperties(includeRequiredValues = true),
	required = listOf("parameter_id", "state", "name"),
)

private fun parameterUpdateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = parameterProperties(includeRequiredValues = false),
	required = listOf("parameter_id", "state"),
)

private fun parameterProperties(includeRequiredValues: Boolean): JsonObject = buildJsonObject {
	putJsonObject("parameter_id") {
		put("type", "string")
		put("description", "Stable Cubism ID. Creation accepts 1-64 ASCII letters/digits/underscores and must start with a letter")
	}
	putJsonObject("state") {
		put("type", "string")
		put("description", "Opaque state from workspace_inspect or the preceding result")
	}
	putJsonObject("name") { put("type", "string") }
	putJsonObject("min") { put("type", "number"); if (includeRequiredValues) put("default", -1) }
	putJsonObject("max") { put("type", "number"); if (includeRequiredValues) put("default", 1) }
	putJsonObject("default") { put("type", "number"); if (includeRequiredValues) put("default", 0) }
	putJsonObject("kind") {
		put("type", "string")
		putJsonArray("enum") { listOf("normal", "blend_shape").forEach { add(JsonPrimitive(it)) } }
		if (includeRequiredValues) put("default", "normal")
	}
	putJsonObject("repeat") { put("type", "boolean"); if (includeRequiredValues) put("default", false) }
	putJsonObject("task_id") { put("type", "string") }
}

private fun addLayerSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
        putJsonObject("registration_id") { put("type", "string"); put("description", "Required for reference-package assets; immutable absolute placement from asset_register") }
		putJsonObject("asset_id") { put("type", "string") }
		putJsonObject("state") {
			put("type", "string")
			put("description", "Opaque state from workspace_inspect or the preceding result")
		}
		putJsonObject("name") { put("type", "string") }
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Optional stable ID; a unique agent: ID is generated when omitted")
		}
		putJsonObject("group_path") { put("type", "string") }
		putJsonObject("semantic_tag") {
			put("type", "string")
			put("description", "SemanticTag enum name in lowercase, for example front_hair")
			put("default", "unknown")
		}
		putJsonObject("side") {
			put("type", "string")
			putJsonArray("enum") { listOf("left", "right", "none").forEach { add(JsonPrimitive(it)) } }
			put("default", "none")
		}
		putJsonObject("insertion") {
			put("type", "object")
			put("description", "Painter-order placement: top, bottom, above or below a stable layer ID")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") { listOf("top", "bottom", "above", "below").forEach { add(JsonPrimitive(it)) } }
				}
				putJsonObject("reference_layer_id") { put("type", "string") }
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("visible") { put("type", "boolean"); put("default", true) }
		putJsonObject("opacity") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("default", 1) }
		putJsonObject("trim_transparent") {
			put("type", "boolean")
			put("default", true)
			put("description", "Crop transparent padding after mapping the complete PNG to canvas units")
		}
		putJsonObject("parent_deformer_id") { put("type", "string") }
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("asset_id", "state", "name"),
)

private fun taskUpdateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("task_id") { put("type", "string") }
		putJsonObject("status") {
			put("type", "string")
			putJsonArray("enum") {
				WorkspaceTaskStatus.entries.forEach { add(JsonPrimitive(it.name.lowercase())) }
			}
		}
		putJsonObject("current_step") {
			put("type", "integer")
			put("minimum", 0)
			put("description", "Zero-based index into the Agent-authored plan")
		}
		putJsonObject("plan") {
			put("type", "array")
			put("description", "Optional replacement for the Agent-authored dynamic plan")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("progress") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("message") { put("type", "string") }
		putJsonObject("artifact_ids") {
			put("type", "array")
			put("description", "View, PNG asset, layer or history node IDs produced at this checkpoint")
			putJsonObject("items") { put("type", "string") }
		}
	},
	required = listOf("task_id", "status"),
)

private fun viewSchema(includeBackground: Boolean, includeFocus: Boolean = false): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Stable layer ID returned by project_list_layers")
		}
		if (includeBackground) {
			putJsonObject("background") {
				put("type", "string")
				putJsonArray("enum") {
					add(JsonPrimitive("transparent"))
					add(JsonPrimitive("checkerboard"))
				}
				put("default", "transparent")
			}
		}
		if (includeFocus) {
			putJsonObject("object_scale") {
				put("type", "number")
				put("minimum", 0.05)
				put("maximum", 4.0)
				put("default", 0.65)
				put("description", "Fraction of the fitted view occupied by the focused layer; values below 1 show surroundings")
			}
			putJsonObject("aspect_ratio") {
				put("type", "number")
				put("minimum", 0.1)
				put("maximum", 10.0)
				put("default", 1.0)
				put("description", "Output frame width divided by height; the layer is fitted without stretching")
			}
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
			put("description", "Maximum encoded PNG bytes; resolution is reduced without changing canvas placement when necessary")
		}
	},
	required = listOf("layer_id"),
)

private fun modelViewSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("annotate_deformer_ids") { put("type","array"); put("maxItems",16); putJsonObject("items") { put("type","string") };put("description","Warp IDs: posed lattice, name and stable ID; [] is a clean image") }
		putJsonObject("annotate_path_ids") { put("type","array"); put("maxItems",32); putJsonObject("items") { put("type","string") };put("description","Deform Path IDs to overlay on the mesh (e.g. ['path_1'] or ['*'] for all paths, or ['L2']/['L3']); [] is a clean image") }
		putJsonObject("annotate_path_width") { put("type","boolean"); put("default",false); put("description","Whether to draw influence width boundaries (outer falloff dashed circle) around deform path control points") }
		putJsonObject("annotate_path_hardness") { put("type","boolean"); put("default",false); put("description","Whether to draw hardness boundaries (inner core solid circle) around deform path control points") }
		putJsonObject("annotate_path_radius") { put("type","boolean"); put("default",false); put("description","Legacy alias for enabling both annotate_path_width and annotate_path_hardness") }
		putJsonObject("point_indices") { put("type","boolean");put("default",false) }
		putJsonObject("parameters") {
			put("type", "object")
			put("description", "Cubism parameter ID to value, for example {ParamAngleX: 10.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("include_layer_ids") {
			put("type", "array")
			put("description", "Exact layer IDs to composite. Omit to use workspace visibility; [] renders no model layers.")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("annotate_layer_ids") {
			put("type", "array")
			put("description", "Layer IDs to label and outline at their deformed positions")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("viewport") {
			put("type", "object")
			put("description", "Required camera: {mode:canvas_rect,left,top,width,height} or {mode:focus_layers,layer_ids,object_scale,aspect_ratio}")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") {
						add(JsonPrimitive("canvas_rect"))
						add(JsonPrimitive("focus_layers"))
					}
				}
				listOf("left", "top", "width", "height", "object_scale", "aspect_ratio").forEach { name ->
					putJsonObject(name) { put("type", "number") }
				}
				putJsonObject("layer_ids") {
					put("type", "array")
					putJsonObject("items") { put("type", "string") }
				}
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("background") {
			put("type", "string")
			putJsonArray("enum") {
				add(JsonPrimitive("transparent"))
				add(JsonPrimitive("checkerboard"))
			}
			put("default", "transparent")
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
		}
	},
	required = listOf("viewport"),
)

private fun targetSchemaProperty(description: String = "Authoritative rig target reference"): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", description)
	putJsonObject("properties") {
		putJsonObject("kind") {
			put("type", "string")
			putJsonArray("enum") {
				listOf("mesh", "warp", "rotation", "part", "glue").forEach { add(JsonPrimitive(it)) }
			}
			put("description", "Object type: mesh, warp, rotation, part, or glue")
		}
		putJsonObject("id") {
			put("type", "string")
			put("description", "Stable object ID (e.g. layer ID for mesh, deformer ID, part ID)")
		}
		putJsonObject("secondary_id") {
			put("type", "string")
			put("description", "Optional secondary ID for composite targets such as glue bindings")
		}
	}
	putJsonArray("required") {
		add(JsonPrimitive("kind"))
		add(JsonPrimitive("id"))
	}
}

private fun geometrySchemaProperty(): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", "Keyform geometry deformation values")
	putJsonObject("properties") {
		putJsonObject("control_points") {
			put("type", "array")
			put("description", "Flat list of [x0, y0, x1, y1, ...] warp lattice control points")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("origin_x") { put("type", "number"); put("description", "Rotation deformer pivot X") }
		putJsonObject("origin_y") { put("type", "number"); put("description", "Rotation deformer pivot Y") }
		putJsonObject("angle") { put("type", "number"); put("description", "Rotation angle in degrees") }
		putJsonObject("scale") { put("type", "number"); put("description", "Rotation scale factor") }
		putJsonObject("position_deltas") {
			put("type", "array")
			put("description", "Flat list of [dx0, dy0, dx1, dy1, ...] vertex deltas for ArtMesh")
			putJsonObject("items") { put("type", "number") }
		}
	}
}

private fun channelsSchemaProperty(): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", "Keyform visual/state channel values")
	putJsonObject("properties") {
		putJsonObject("opacity") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("draw_order") { put("type", "number") }
		putJsonObject("multiply_color") {
			put("type", "array")
			put("description", "Normalized RGBA [r, g, b, a] multiply color")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("screen_color") {
			put("type", "array")
			put("description", "Normalized RGB [r, g, b] screen color")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("glue_intensity") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("flip_x") { put("type", "boolean") }
		putJsonObject("flip_y") { put("type", "boolean") }
	}
}

private fun objectGetSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		put("target", targetSchemaProperty())
		putJsonObject("kind") {
			put("type", "string")
			putJsonArray("enum") {
				listOf("mesh", "warp", "rotation", "part", "glue").forEach { add(JsonPrimitive(it)) }
			}
		}
		putJsonObject("id") { put("type", "string") }
		putJsonObject("secondary_id") { put("type", "string") }
	},
)

private fun keyformSetSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("state") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("coordinate") {
			put("type", "object")
			put("description", "Keyform parameter coordinate, e.g. {\"ParamAngleX\": 0.0, \"ParamAngleY\": 1.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("geometry", geometrySchemaProperty())
		put("channels", channelsSchemaProperty())
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("state", "coordinate"),
)

private fun keyformDeleteSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("state") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("parameter_id") { put("type", "string") }
		putJsonObject("key_value") {
			put("type", "number")
			put("description", "Specific key value to remove. If omitted, the entire parameter axis is deleted.")
		}
		putJsonObject("channel") {
			put("type", "string")
			put("description", "Optional channel name (e.g. opacity, draw_order). If omitted, operates on geometry.")
		}
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("state", "parameter_id"),
)

private fun keyformCopySchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("state") { put("type", "string") }
		put("source_target", targetSchemaProperty("Source target reference"))
		putJsonObject("source_coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("destination_target", targetSchemaProperty("Destination target (defaults to source if omitted)"))
		putJsonObject("destination_coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("channels") {
			put("type", "array")
			put("description", "Optional list of channels to copy (e.g. [\"geometry\", \"opacity\"]). Null copies all.")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("state", "source_coordinate", "destination_coordinate"),
)

private fun rigKPoseSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("state") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("parameters") {
			put("type", "object")
			put("description", "Pose parameters to set/capture, e.g. {\"ParamAngleX\": 30.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("geometry", geometrySchemaProperty())
		put("channels", channelsSchemaProperty())
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("state"),
)

private suspend fun renderResult(block: suspend () -> WorkspaceRenderedView): WorkspaceOperationOutput {
    val view = block()
    return WorkspaceOperationOutput(view.toJson(), listOf(view.png))
}
private suspend fun mutationResult(block: suspend () -> JsonObject): WorkspaceOperationOutput = WorkspaceOperationOutput(block())
private fun jsonResult(json: JsonObject) = WorkspaceOperationOutput(json)

private fun WorkspaceCommandInput.requiredString(name: String): String =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("Missing required argument: $name")

private fun WorkspaceCommandInput.optionalString(name: String): String? =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun WorkspaceCommandInput.boolean(name: String, default: Boolean): Boolean =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
		?: if (arguments.containsKey(name) == true) throw IllegalArgumentException("$name must be a boolean") else default

private fun WorkspaceCommandInput.optionalBoolean(name: String): Boolean? =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull()
			?: throw IllegalArgumentException("$name must be a boolean")
	}

private fun WorkspaceCommandInput.optionalInteger(name: String): Int? =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
	}

private fun WorkspaceCommandInput.optionalFloat(name: String): Float? =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	}

private fun WorkspaceCommandInput.stringList(
	name: String,
	required: Boolean = true,
): List<String> {
	val value = arguments.get(name)
	if (value == null) {
		if (required) throw IllegalArgumentException("Missing required argument: $name")
		return emptyList()
	}
	return value.jsonArray.map { item ->
		item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
			?: throw IllegalArgumentException("$name must contain non-empty strings")
	}
}

private fun WorkspaceCommandInput.optionalStringList(name: String): List<String>? =
	if (arguments.containsKey(name) == true) stringList(name) else null

private fun WorkspaceCommandInput.outputSpec(): WorkspaceViewOutputSpec =
	WorkspaceViewOutputSpec(
		targetLongEdge = integerOrDefault("target_long_edge", integerOrDefault("max_edge", 1024)),
		maxBytes = integerOrDefault("max_bytes", 4 * 1024 * 1024),
	)

private fun WorkspaceCommandInput.integerOrDefault(name: String, default: Int): Int =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
	} ?: default

private fun WorkspaceCommandInput.float(name: String, default: Float): Float =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	} ?: default

private fun WorkspaceCommandInput.background(): WorkspaceViewBackground =
	arguments.get("background")?.jsonPrimitive?.contentOrNull
		?.uppercase()?.let(WorkspaceViewBackground::valueOf) ?: WorkspaceViewBackground.TRANSPARENT

private fun WorkspaceCommandInput.floatMap(name: String): Map<String, Float> =
	arguments.get(name)?.jsonObject?.mapValues { (id, value) ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Parameter $id must be numeric")
	}.orEmpty()

private fun WorkspaceCommandInput.optionalStringSet(name: String): Set<String>? =
	arguments.get(name)?.jsonArray?.map { value ->
		value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
			?: throw IllegalArgumentException("$name must contain non-empty strings")
	}?.toSet()

private fun WorkspaceCommandInput.viewFrame(): WorkspaceViewFrame {
	val viewport = arguments.get("viewport")?.jsonObject
		?: throw IllegalArgumentException("Missing required argument: viewport")
	fun requiredFloat(name: String): Float = viewport[name]?.jsonPrimitive?.floatOrNull
		?: throw IllegalArgumentException("viewport.$name must be numeric")
	fun optionalFloat(name: String, default: Float): Float = viewport[name]?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("viewport.$name must be numeric")
	} ?: default
	return when (viewport["mode"]?.jsonPrimitive?.contentOrNull) {
		"canvas_rect" -> {
			val left = requiredFloat("left")
			val top = requiredFloat("top")
			val width = requiredFloat("width")
			val height = requiredFloat("height")
			WorkspaceViewFrame.CanvasRect(Bounds(left, top, left + width, top + height))
		}

		"focus_layers" -> {
			val layerIds = viewport["layer_ids"]?.jsonArray?.map { value ->
				value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
					?: throw IllegalArgumentException("viewport.layer_ids must contain non-empty strings")
			}?.toSet().orEmpty()
			WorkspaceViewFrame.FocusLayers(
				layerIds = layerIds,
				objectScale = optionalFloat("object_scale", 0.65f),
				aspectRatio = optionalFloat("aspect_ratio", 1f),
			)
		}

		null -> throw IllegalArgumentException("Missing required argument: viewport.mode")
		else -> throw IllegalArgumentException("viewport.mode must be canvas_rect or focus_layers")
	}
}

private fun WorkspaceCommandInput.layerInsertion(): WorkspaceLayerInsertion {
	val insertion = arguments.get("insertion")?.jsonObject ?: return WorkspaceLayerInsertion.Top
	val mode = insertion["mode"]?.jsonPrimitive?.contentOrNull
		?: throw IllegalArgumentException("insertion.mode is required")
	fun reference(): String = insertion["reference_layer_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("insertion.reference_layer_id is required for $mode")
	return when (mode) {
		"top" -> WorkspaceLayerInsertion.Top
		"bottom" -> WorkspaceLayerInsertion.Bottom
		"above" -> WorkspaceLayerInsertion.Above(reference())
		"below" -> WorkspaceLayerInsertion.Below(reference())
		else -> throw IllegalArgumentException("insertion.mode must be top, bottom, above, or below")
	}
}

private fun WorkspaceCommandInput.hasTarget(prefix: String = "target"): Boolean {
	if (arguments.containsKey(prefix) == true) return true
	if (arguments.containsKey("${prefix}_kind") == true && arguments.containsKey("${prefix}_id") == true) return true
	if (prefix == "target" && arguments.containsKey("kind") == true && arguments.containsKey("id") == true) return true
	return false
}

private fun WorkspaceCommandInput.targetRef(
	prefix: String = "target",
): WorkspaceKeyformTargetRef {
	val obj = (arguments.get(prefix) ?: if (prefix == "source_target") arguments.get("target") else null)?.jsonObject
	if (obj != null) {
		val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
			?: throw IllegalArgumentException("$prefix.kind is required")
		val id = obj["id"]?.jsonPrimitive?.contentOrNull
			?: throw IllegalArgumentException("$prefix.id is required")
		val secondaryId = obj["secondary_id"]?.jsonPrimitive?.contentOrNull
			?: obj["secondaryId"]?.jsonPrimitive?.contentOrNull
		return WorkspaceKeyformTargetRef(kind = kind, id = id, secondaryId = secondaryId)
	}
	val kind = arguments.get("${prefix}_kind")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments.get("target_kind")?.jsonPrimitive?.contentOrNull ?: arguments.get("kind")?.jsonPrimitive?.contentOrNull else null)
		?: throw IllegalArgumentException("Missing target kind")
	val id = arguments.get("${prefix}_id")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments.get("target_id")?.jsonPrimitive?.contentOrNull ?: arguments.get("id")?.jsonPrimitive?.contentOrNull else null)
		?: throw IllegalArgumentException("Missing target id")
	val secondaryId = arguments.get("${prefix}_secondary_id")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments.get("secondary_id")?.jsonPrimitive?.contentOrNull else null)
	return WorkspaceKeyformTargetRef(kind = kind, id = id, secondaryId = secondaryId)
}

private fun WorkspaceCommandInput.coordinateMap(
	name: String = "coordinate",
): Map<String, Float> {
	val obj = arguments.get(name)?.jsonObject
		?: throw IllegalArgumentException("Missing required argument: $name")
	return obj.mapValues { (k, v) ->
		v.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Coordinate $k must be numeric")
	}
}

private fun WorkspaceCommandInput.optionalGeometry(
	name: String = "geometry",
): WorkspaceKeyformGeometry? {
	val obj = arguments.get(name)?.jsonObject ?: return null
	fun fl(key: String, alt: String? = null): Float? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.floatOrNull
	fun flList(key: String, alt: String? = null): List<Float>? {
		val arr = (obj[key] ?: alt?.let { obj[it] })?.jsonArray ?: return null
		return arr.map { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$key must contain numbers") }
	}
	return WorkspaceKeyformGeometry(
		controlPoints = flList("control_points", "controlPoints"),
		originX = fl("origin_x", "originX"),
		originY = fl("origin_y", "originY"),
		angle = fl("angle"),
		scale = fl("scale"),
		positionDeltas = flList("position_deltas", "positionDeltas"),
	)
}

private fun WorkspaceCommandInput.optionalChannels(
	name: String = "channels",
): WorkspaceKeyformChannels? {
	val obj = arguments.get(name)?.jsonObject ?: return null
	fun fl(key: String, alt: String? = null): Float? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.floatOrNull
	fun bl(key: String, alt: String? = null): Boolean? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
	fun flList(key: String, alt: String? = null): List<Float>? {
		val arr = (obj[key] ?: alt?.let { obj[it] })?.jsonArray ?: return null
		return arr.map { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$key must contain numbers") }
	}
	return WorkspaceKeyformChannels(
		opacity = fl("opacity"),
		drawOrder = fl("draw_order", "drawOrder"),
		multiplyColor = flList("multiply_color", "multiplyColor"),
		screenColor = flList("screen_color", "screenColor"),
		glueIntensity = fl("glue_intensity", "glueIntensity"),
		flipX = bl("flip_x", "flipX"),
		flipY = bl("flip_y", "flipY"),
	)
}

internal fun WorkspaceProjectSnapshot.toJson(
	includeLayers: Boolean,
	layers: List<WorkspaceLayerSnapshot> = this.layers,
): JsonObject = buildJsonObject {
	projectId?.let { put("projectId", it) }
	put("revisionId", revisionId)
    put("state", state)
	historyHeadNodeId?.let { put("historyHeadNodeId", it) }
    projectFile?.let { put("projectFile", it) }
    put("projectDirty", projectDirty)
    put("projectSaving", projectSaving)
    projectSaveError?.let { put("projectSaveError", it) }
	put("loaded", loaded)
	inputName?.let { put("inputName", it) }
	canvasWidth?.let { put("canvasWidth", it) }
	canvasHeight?.let { put("canvasHeight", it) }
	put("busy", busy)
	put("status", status)
	put("persistenceStatus", persistenceStatus)
	persistenceError?.let { put("persistenceError", it) }
	selectedLayerId?.let { put("selectedLayerId", it) }
	put("layerCount", layers.size)
	put("totalLayerCount", this@toJson.layers.size)
	put("parameterCount", parameters.size)
	if (includeLayers) put("layers", JsonArray(layers.map(WorkspaceLayerSnapshot::toJson)))
}

private fun WorkspaceParameterSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("name", name)
	put("min", min)
	put("max", max)
	put("default", default)
	put("current", current)
	put("kind", kind)
}

private fun WorkspaceLayerSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("sourceName", sourceName)
	put("rasterWidth", rasterWidth)
	put("rasterHeight", rasterHeight)
	put("canvasUnitsPerSourcePixelX", bounds.width / rasterWidth.coerceAtLeast(1).toFloat())
	put("canvasUnitsPerSourcePixelY", bounds.height / rasterHeight.coerceAtLeast(1).toFloat())
	put("groupPath", groupPath)
	put("order", order)
	put("semanticTag", semanticTag)
	put("side", side)
	put("classificationType", classificationType)
	put("parameterBinding", parameterBinding)
	put("switchId", switchId)
	put("confidence", confidence)
	put("visible", visible)
	put("deleted", deleted)
	put("derived", derived)
	sourceAssetId?.let { put("sourceAssetId", it) }
	sourceSpatialReferenceId?.let { put("sourceSpatialReferenceId", it) }
	putJsonObject("bounds") {
		put("left", bounds.left)
		put("top", bounds.top)
		put("right", bounds.right)
		put("bottom", bounds.bottom)
	}
	putJsonArray("sourcePixelToCanvas") {
		add(JsonPrimitive(bounds.width / rasterWidth.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.left))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.height / rasterHeight.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(bounds.top))
	}
	putJsonObject("opaqueBounds") {
		put("left", opaqueBounds.left)
		put("top", opaqueBounds.top)
		put("right", opaqueBounds.right)
		put("bottom", opaqueBounds.bottom)
	}
}

private fun WorkspaceHistorySnapshot.toJson(): JsonObject = buildJsonObject {
	put("headNodeId", headNodeId)
	putJsonArray("nodes") {
		nodes.forEach { node ->
			add(buildJsonObject {
				put("id", node.id)
				node.parentId?.let { put("parentId", it) }
				put("revisionId", node.revisionId)
				put("summary", node.summary)
				put("actor", node.actor)
				node.taskId?.let { put("taskId", it) }
				put("createdAt", node.createdAt)
				put("isHead", node.isHead)
			})
		}
	}
}

internal fun WorkspaceImportedPngAsset.toJson(): JsonObject = buildJsonObject {
    put("details", details)
	put("assetId", id)
	put("sha256", sha256)
	put("mimeType", "image/png")
	put("pixelWidth", pixelWidth)
	put("pixelHeight", pixelHeight)
	put("sourceSpatialReferenceId", placement.sourceViewId)
	putJsonObject("canvasRect") {
		put("left", placement.canvasRect.left)
		put("top", placement.canvasRect.top)
		put("right", placement.canvasRect.right)
		put("bottom", placement.canvasRect.bottom)
		put("width", placement.canvasRect.width)
		put("height", placement.canvasRect.height)
	}
	put("canvasUnitsPerPixelX", placement.canvasUnitsPerPixelX)
	put("canvasUnitsPerPixelY", placement.canvasUnitsPerPixelY)
}

internal fun WorkspaceMutationResult.toJson(): JsonObject = buildJsonObject {
	put("historyNodeId", historyNodeId)
    state?.let { put("state", it) }
    projectId?.let { put("project_id", it) }
	put("revisionId", revisionId)
	put("summary", summary)
	// False means the request described the state the workspace was already in: no node was appended and
	// nothing needs re-reading. Stated on every response so a caller never has to infer it from an
	// unchanged revision.
	put("applied", applied)
	putJsonArray("affectedLayerIds") { affectedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedParameterIds") { affectedParameterIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedObjectIds") { affectedObjectIds.forEach { add(JsonPrimitive(it)) } }
}

private fun WorkspaceObjectSnapshot.toJson(): JsonObject = buildJsonObject {
	putJsonObject("target") {
		put("kind", target.kind)
		put("id", target.id)
		target.secondaryId?.let { put("secondaryId", it) }
	}
	put("name", name)
	parentId?.let { put("parentId", it) }
	partId?.let { put("partId", it) }
	put("visible", visible)
	if (topologyInfo.isNotEmpty()) {
		putJsonObject("topologyInfo") {
			topologyInfo.forEach { (k, v) -> put(k, v) }
		}
	}
	geometry?.let { geom ->
		putJsonObject("geometry") {
			put("keyformCount", geom.keyformCount)
			putJsonArray("axes") {
				geom.axes.forEach { axis ->
					add(buildJsonObject {
						put("parameterId", axis.parameterId)
						putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
					})
				}
			}
			putJsonArray("cells") {
				geom.cells.forEach { cell ->
					add(buildJsonObject {
						putJsonObject("coordinate") {
							cell.coordinate.forEach { (p, v) -> put(p, v) }
						}
						cell.originX?.let { put("originX", it) }
						cell.originY?.let { put("originY", it) }
						cell.angle?.let { put("angle", it) }
						cell.scale?.let { put("scale", it) }
						cell.controlPoints?.let { cps ->
							putJsonArray("controlPoints") { cps.forEach { cp -> add(JsonPrimitive(cp)) } }
						}
						cell.positionDeltas?.let { pds ->
							putJsonArray("positionDeltas") { pds.forEach { pd -> add(JsonPrimitive(pd)) } }
						}
					})
				}
			}
		}
	}
	if (channels.isNotEmpty()) {
		putJsonArray("channels") {
			channels.forEach { track ->
				add(buildJsonObject {
					put("channel", track.channel)
					put("staticValue", track.staticValue)
					put("keyformCount", track.keyformCount)
					putJsonArray("axes") {
						track.axes.forEach { axis ->
							add(buildJsonObject {
								put("parameterId", axis.parameterId)
								putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
							})
						}
					}
				})
			}
		}
	}
}

private fun WorkspaceTaskSnapshot.toJson(includeEvents: Boolean = true): JsonObject = buildJsonObject {
	put("taskId", id)
	put("objective", objective)
	putJsonArray("plan") { plan.forEach { add(JsonPrimitive(it)) } }
	put("status", status.name.lowercase())
	currentStep?.let { put("currentStep", it) }
	put("progress", progress)
	put("inputRevisionId", inputRevisionId)
	put("inputHistoryHeadNodeId", inputHistoryHeadNodeId)
	put("createdAt", createdAt)
	put("updatedAt", updatedAt)
	putJsonArray("artifactIds") { artifactIds.forEach { add(JsonPrimitive(it)) } }
	if (includeEvents) putJsonArray("events") {
		events.forEach { event ->
			add(buildJsonObject {
				put("sequence", event.sequence)
				put("createdAt", event.createdAt)
				put("status", event.status.name.lowercase())
				put("message", event.message)
				putJsonArray("artifactIds") { event.artifactIds.forEach { add(JsonPrimitive(it)) } }
			})
		}
	}
}

internal fun WorkspaceRenderedView.toJson(): JsonObject = buildJsonObject {
	put("viewId", viewId)
	put("revisionId", revisionId)
	put("kind", kind)
	put("mimeType", "image/png")
	put("pngBytes", png.size)
	put("sha256", sha256)
	put("originalWidth", originalWidth)
	put("originalHeight", originalHeight)
	put("renderedWidth", renderedWidth)
	put("renderedHeight", renderedHeight)
	put("scale", scale)
	putJsonObject("appliedParameters") {
		appliedParameters.forEach { (id, value) -> put(id, value) }
	}
	putJsonArray("outOfRangeParameters") {
		outOfRangeParameters.forEach { diagnostic ->
			add(buildJsonObject {
				put("id", diagnostic.id)
				put("value", diagnostic.value)
				put("min", diagnostic.min)
				put("max", diagnostic.max)
			})
		}
	}
	putJsonArray("includedLayerIds") { includedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedLayerIds") { annotatedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedDeformerIds") { annotatedDeformerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedPathIds") { annotatedPathIds.forEach { add(JsonPrimitive(it)) } }
	put("annotatedPathWidth", annotatedPathWidth)
	put("annotatedPathHardness", annotatedPathHardness)
	put("annotatedPathRadius", annotatedPathRadius)
	put("pointIndices",pointIndices)
	putJsonArray("objectIds") { objectIds.forEach { add(JsonPrimitive(it)) } }
	putJsonObject("canvasRect") {
		put("left", canvasRect.left)
		put("top", canvasRect.top)
		put("right", canvasRect.right)
		put("bottom", canvasRect.bottom)
	}
	putJsonObject("spatial") {
		put("spatialReferenceId", viewId)
		put("coordinateSpace", spatial.coordinateSpace)
		put("pixelOrigin", "top_left_edge")
		put("pixelWidth", spatial.pixelWidth)
		put("pixelHeight", spatial.pixelHeight)
		put("pixelAspectRatio", 1)
		put("colorSpace", "sRGB")
		put("alphaMode", "straight")
		put("canvasWidth", spatial.canvasWidth)
		put("canvasHeight", spatial.canvasHeight)
		put("canvasUnitsPerPixelX", spatial.canvasUnitsPerPixelX)
		put("canvasUnitsPerPixelY", spatial.canvasUnitsPerPixelY)
		putJsonObject("requestedViewRect") {
			put("left", spatial.requestedViewRect.left)
			put("top", spatial.requestedViewRect.top)
			put("right", spatial.requestedViewRect.right)
			put("bottom", spatial.requestedViewRect.bottom)
			put("width", spatial.requestedViewRect.width)
			put("height", spatial.requestedViewRect.height)
		}
		putJsonObject("viewRect") {
			put("left", spatial.viewRect.left)
			put("top", spatial.viewRect.top)
			put("right", spatial.viewRect.right)
			put("bottom", spatial.viewRect.bottom)
			put("width", spatial.viewRect.width)
			put("height", spatial.viewRect.height)
		}
		spatial.focusRect?.let { focus ->
			putJsonObject("focusRect") {
				put("left", focus.left)
				put("top", focus.top)
				put("right", focus.right)
				put("bottom", focus.bottom)
				put("width", focus.width)
				put("height", focus.height)
			}
		}
		putJsonArray("focusLayerIds") { spatial.focusLayerIds.forEach { add(JsonPrimitive(it)) } }
		spatial.objectScale?.let { put("objectScale", it) }
		putJsonArray("pixelToCanvas") {
			add(JsonPrimitive(spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.viewRect.left))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(spatial.viewRect.top))
		}
		putJsonArray("canvasToPixel") {
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(-spatial.viewRect.left / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(-spatial.viewRect.top / spatial.canvasUnitsPerPixelY))
		}
		put("placementRule", "map_full_png_to_view_rect_preserve_aspect")
	}
}
