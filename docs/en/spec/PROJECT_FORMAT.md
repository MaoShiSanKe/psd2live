# Project format v1

[Documentation](../../README.md) · [中文](../../zh/spec/PROJECT_FORMAT.md) · [User guide](../guide/USER_GUIDE.md)

`.psd2live` is an unencrypted ZIP containing UTF-8 JSON and PNG raster resources. A saved project carries its source artwork and history without relying on the original PSD path. Export reports named `.psd2live.json` are not projects.

## Archive layout

| Path | Content |
| --- | --- |
| `manifest.json` | Format, version, project UUID and SHA-256 inventory |
| `source/original.psd` or `source/original.cmo3` | Original imported source; artwork-created projects generate a PSD source |
| `workspace.json` | Durable UI layout, camera, selection, parameter preview, annotations and logs |
| `images/<hash>.png` | Log images |
| `workspace/<projectId>/HEAD.json` | Current node and insertion order |
| `workspace/<projectId>/history/nodes/` | Immutable parent-linked nodes and metadata |
| `workspace/<projectId>/history/snapshots/` | Source layers, settings, structure and edit overlays |
| `workspace/<projectId>/blobs/` | Deduplicated RGBA rasters encoded as PNG |
| `workspace/<projectId>/assets/` | Staged artwork metadata |
| `workspace/<projectId>/views/`, `view-images/` | Spatial references and rendered images |
| `workspace/<projectId>/workflow/` | Reference packages, registrations and placement records |
| `workspace/<projectId>/tasks.json` | Agent task records and events |

Internal filenames may hash logical IDs rather than display names. PNG resources retain RGB under transparent alpha. Snapshots share rasters; auxiliary entries depend on features used.

## Save and recovery

Saves capture immutable state and serialize writes. The writer builds and validates a temporary archive beside the destination, then replaces the destination atomically. Unsupported atomic replacement fails while keeping the previous project. Later edits remain unsaved after an earlier capture completes.

Ordinary saves do not append a history node when content already matches HEAD. Explicit checkpoints may record unchanged content. A failed save is not durable completion; inspect the error state.

All branches are retained. Undo follows the parent, redo selects a successor, and editing from an old node creates a branch. Renaming, annotating or hiding branches does not rewrite original nodes or remove assets.

New parameter creation, updates and deletion are stored in `rigEdits.authoringJournal` and replay in edit order. Deletion therefore runs after earlier keyforms and the last default-value update. Legacy v1 static parameter overrides keep their original replay order; existing snapshots, revisions and node IDs are not rewritten. Channel keyforms persist alongside geometry edits.

The internal `canvas_mesh_rebuild` journal record stores parent-local vertices, triangles, texture coordinates in source canvas pixels, old vertex interpolation sources and glue vertex mappings. Optional `neutral_bounds` records the replacement mesh's neutral canvas bounds. A first replacement of an imported CMO3 canvas base can include `previous_parent_points`: replay rebases ordinary and blend forms onto those neutral parent coordinates before interpolation, and converts paths through their original triangles. Replay checks the previous geometry SHA-256, parent and artwork identity, reconstructs UVs against the current atlas, and migrates keyforms, blend shapes, paths, vertex groups and glue. Mesh-only setting changes retain preceding journal and creation records and append replacements without rewriting history. The archive version remains 1. See the [refactor progress](../../zh/agent/REFACTOR_PROGRESS.md) for remaining classification and generation-mode migration.

The internal `canvas_mesh_create` record captures meshes absent from the original generation input: stable ID, source and layer identity, transparent coverage bounds, parent and part, static material, local vertices/triangles, canvas texture coordinates, generated parameters/keyforms/channels and paths. Replay resolves the current atlas and requires an unused mesh ID and existing parent and artwork, validating geometry, keyforms and raster dimensions. First visible painting on an ordinary transparent layer or mouth creates meshes automatically; subsequent clearing retains their geometry and bindings. Mesh settings update creation geometry in the current candidate and persist rebound paths and weights while previous history snapshots remain immutable. The archive version remains 1. Complete classification changes and imported objects without meshes remain pending.

The internal `canvas_source_partition` record stores the source mesh identity, local-geometry fingerprint, parent, original-vertex ownership and each piece's mesh/layer identity, name, visibility, local geometry, canvas texture coordinates and vertex ancestry. Replay copies bindings at their original journal position and resolves the current atlas; deleted source meshes are filtered afterwards. Component Glue retains pair order. Simulation targets, materialized offsets and Glue roles migrate in the same document candidate. Transparent generation placeholders retain texture coverage without creating duplicate meshes. Historical nodes remain unchanged and the archive version stays 1. Live Glue interpolation for polygon cuts and imported source partitions remain pending.

The internal `mesh_generation_baseline` marker retains the preceding global mesh settings, per-layer overrides and alpha threshold for base generation. Current document settings remain authoritative for requested regeneration. Subsequent layer/global updates and resets regenerate from saved mesh input or current pixels, convert through the actual neutral parent, and append ordered `canvas_mesh_rebuild` records. Ordinary, partitioned, created and imported CMO3 meshes share this replacement path. Ordinary/blend forms, paths, vertex groups, Glue and simulation offsets migrate to the new topology. Bakes update vertex counts while retaining their original fingerprint and quality metrics so stale-input diagnostics remain honest. Hidden meshes regenerate before filtering and use the replacement topology on restoration. Older documents gain the marker only in a new candidate after an actual mesh change; earlier revisions, history nodes and archive version remain unchanged. Imported models normalize only replaced texture addresses. Fully transparent artwork retains its authored topology. Each replacement interpolates the preceding topology's offsets; resetting settings after coarsening cannot recover discarded high-frequency detail. Complete source, classification and generation-mode migration remains pending.

Soft deletion uses the document's existing `deletedLayerIds`, retaining source pixels, ordered authoring and earlier immutable history nodes. Projects containing creation records, depth splits or an internal `layer_membership` marker rebuild and replay the complete input before filtering deleted layers and derived lips from the active preview or export, removing mask references, glue, paths and vertex groups. Restoration replays saved IDs and bindings. The first actual deletion or restoration in an ordinary project fixes the current generation range and all source/Drawable identities in the new candidate, adding a `layer_membership` marker containing only `op`; restoration retains this identity baseline. Imported CMO3 models keep their original IDs and coordinate frames and only add the marker. Earlier documents without these records retain their original generation rules without automatic upgrades or rewriting revisions and nodes. Mesh settings changed during deletion also regenerate hidden meshes and persist rebound paths and weights; atlas constraints use the complete input. GUI and MCP deletion and selected/all restoration share the candidate commit, with repeated operations adding no history. Existing journal and baseline fields are used; the archive version remains 1.

An optional `generationSource` in a history document uses the same canvas, layer and raster-blob encoding as the current artwork. It retains the original raster shapes used to generate the base rig while `source` supplies painted pixels. Preview and export generate the same geometry, remap UVs through source canvas coordinates, and pad transparent coverage after a tighter crop so kept meshes cannot sample a neighboring tile. Optional `meshSource` uses the same encoding and updates the explicitly rebuilt or first-created layer's input, preserving generated lip contours and colors during later painting that keeps the mesh. Updating generated texture inputs is a durable change even when vertices stay the same. Optional `placementSource` uses the same source encoding to retain the cropped and fitted pixels of file-imported images. Repeated placement resizes from those pixels rather than the previous scaled result. All four sources and history nodes share deduplicated PNG blobs. Omitting the new fields preserves existing revisions and node identities.

Captured GUI pixels and MCP raster gestures prepare the same document candidate. Clearing all pixels retains the layer, base mesh and authored bindings even when mesh rebuilding is requested. CMO3 painting keeps unrelated meshes' original atlas and UVs, adding painted texture pages only for modified meshes. Complex created-object migration, complete artwork/classification migration and full GUI coverage remain in progress; see the [acceptance record](../../zh/agent/REFACTOR_PROGRESS.md).

Runtime previews rebuild from source, settings and replayable edits. Native handles, connections, active jobs and animation clocks are not serialized. Agent task records do not automatically restart execution.

Committed parameter poses and locks are held per workspace in runtime auxiliary state and projected to the existing workspace/canvas fields in v1 `workspace.json`; the archive version is unchanged. Saving uses captured durable values and locks for every workspace, excluding transient GUI values and evaluated frame caches. Queries, preview changes and snapshot application normalize older poses against current parameter definitions without rewriting records or history on reads or logical no-ops. Duplicating a workspace registers its copied durable pose.

Optional `assetCatalog` in `workspace.json` stores `{version:1, assets:[ID...], workflow:[ID...]}`, the committed membership of assets and reference/registration records. Resources retain their existing auxiliary directories and do not enter the Rig journal. Asset writes advance durable runtime state without changing the Rig revision or history. Saving copies resources from the captured catalog, excluding later submissions. Opening legacy v1 files without a catalog freezes membership from existing immutable records without rewriting history; new projects start with an empty catalog. Opening validates member existence, project identity and reference relationships. Inspection and composition use captured membership; failures and cancellation before commit publish no candidate files.

## Validation

Opening validates version, inventory, hashes, rasters, history references and HEAD. Duplicate entries, escaping paths and unsupported versions are rejected. Extraction limits are 1,000,000 entries and 64 GiB of actual decompressed bytes, not declared ZIP sizes.

Manual edits must preserve references and update inventory hashes. Use the UI for ordinary editing and history work. Legacy `.rgba.gz` recovery resources remain a compatibility read path, not the primary write format.

## Entry points

- Import PSD: `Ctrl+Shift+O`; open project: `Ctrl+O`.
- Save / save as: `Ctrl+S` / `Ctrl+Shift+S`.
- MCP: `project_save`, `project_save_as`, `project_open`, and `history_checkpoint`, `history_list`, `history_checkout`. Save-as and open use absolute paths without requiring a UI file selection. Mutations carry the current `project_id`, opaque `state` and unique `request_id`; reopening the same node invalidates earlier state tokens. Public summaries do not expose every internal persistence field; see the [MCP contract](../../zh/agent/MCP_AUTHORING.md).

[ProjectArchive](../../../src/main/kotlin/io/github/psd2live/project/ProjectArchive.kt) · [ProjectRepository](../../../src/main/kotlin/io/github/psd2live/project/ProjectRepository.kt) · [WorkspaceStore](../../../src/main/kotlin/io/github/psd2live/project/WorkspaceStore.kt)

New independent Warp creation uses ordered `authoringJournal` entries with stable IDs, parent and mesh references, and fitting options. New commands do not append legacy static Warp/structure records. Existing v1 records retain their original replay order and immutable history nodes.
