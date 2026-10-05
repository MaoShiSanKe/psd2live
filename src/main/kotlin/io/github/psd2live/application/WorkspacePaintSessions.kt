package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkspacePaintStroke(val id: String, val name: String)

/** One private raster and stroke history, shared by UI and public session controls. */
class WorkspacePaintSession internal constructor(
    val id: String, val projectId: String, val workspaceState: String,
    val layerId: String, val layerName: String, image: BufferedImage,
    private val validateWorkspace: () -> Unit,
) {
    private val lock = Any()
    private val buffer = PaintRasterSession(layerId, layerName, image, copy(image))
    private var version = 0L
    private var active = false
    private var committing = false
    private var closed = false
    private var completion: WorkspaceMutationResult? = null
    private val observers = linkedSetOf<(List<Rectangle>) -> Unit>()
    private val changedRegions = mutableListOf<Rectangle>()
    val width: Int get() = imageWidth
    val height: Int get() = imageHeight
    private val imageWidth = image.width
    private val imageHeight = image.height
    val sessionState: String get() = synchronized(lock) { token() }
    val isDirty: Boolean get() = synchronized(lock) { buffer.isDirty }
    val activeStroke: Boolean get() = synchronized(lock) { active }
    val finished: Boolean get() = synchronized(lock) { closed }
    val index: Int get() = synchronized(lock) { buffer.currentStrokeIndex }
    val strokes: List<WorkspacePaintStroke> get() = synchronized(lock) {
        buffer.strokeRecords.map { WorkspacePaintStroke(it.id, it.name) }
    }
    init { buffer.onChanged = { regions -> changedRegions.addAll(regions) } }
    internal fun <T> atomic(expected: String, block: () -> T): T = synchronized(lock) {
        if (expected != token()) throw WorkspaceConflict(expected, token())
        block()
    }
    fun observe(observer: (List<Rectangle>) -> Unit): () -> Unit {
        synchronized(lock) { observers += observer }
        return { synchronized(lock) { observers -= observer } }
    }
    fun image(): BufferedImage = synchronized(lock) { copy(buffer.workingImage) }
    fun tile(x: Int, y: Int, width: Int, height: Int): BufferedImage = synchronized(lock) {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, width, height, buffer.workingImage.getRGB(x, y, width, height, null, 0, width), 0, width)
        }
    }
    fun sample(x: Int, y: Int): Int = synchronized(lock) {
        require(x in 0 until width && y in 0 until height) { "Sample point is outside the canvas" }
        buffer.workingImage.getRGB(x, y)
    }
    fun canUndo(): Boolean = synchronized(lock) { !closed && !active && buffer.canUndo() }
    fun canRedo(): Boolean = synchronized(lock) { !closed && !active && buffer.canRedo() }
    private fun token() = "$id:$version"
    private fun writable(expected: String? = null, allowActive: Boolean = false) {
        expected?.let { if (it != token()) throw WorkspaceConflict(it, token()) }
        check(!closed) { "Paint session is closed" }
        if (committing || (active && !allowActive)) throw WorkspaceBusy()
        validateWorkspace()
    }
    private fun publish() {
        val regions = changedRegions.toList(); changedRegions.clear()
        observers.toList().forEach { runCatching { it(regions) } }
    }
    private fun changed() { version++; publish() }
    fun beginStroke() = synchronized(lock) {
        writable(); active = true; buffer.beginStroke(); changed()
    }
    internal fun segment(x0: Float, y0: Float, x1: Float, y1: Float, tip: RasterPaintEngine.Tip,
        color: Int, opacity: Float, erase: Boolean) = synchronized(lock) {
        writable(allowActive = true); check(active) { "No live paint stroke" }
        buffer.stroke().addSegment(x0, y0, x1, y1, tip)?.let {
            buffer.landSegment(it, color, opacity, erase); changed()
        }
    }
    fun recordStroke(name: String) = synchronized(lock) {
        writable(allowActive = true); buffer.recordStroke(name); active = false; changed()
    }
    fun abandonStroke(expected: String? = null) = synchronized(lock) {
        writable(expected, allowActive = true); buffer.abandonStroke(); active = false; changed()
    }
    fun gesture(request: JsonObject, name: String = request.getValue("mode").jsonPrimitive.content,
        expected: String? = null, checkpoint: () -> Unit = {}) = synchronized(lock) {
        writable(expected)
        validateOperationSchema(request, WorkspacePaintSessionSchemas.gesture)
        // One tile patch is the rollback boundary for a fill, shape or complete public stroke.
        try {
            paintRasterGesture(buffer.workingImage, request, object : WorkspaceRasterWork {
                override fun checkpoint() = checkpoint()
                override fun progress(fraction: Float, message: String) {}
            }, before = { checkpoint(); buffer.willWrite(it) })
            checkpoint(); buffer.recordStroke(name); changed()
        } catch (failure: Throwable) { buffer.abandonStroke(); publish(); throw failure }
    }
    fun undo(expected: String? = null) = synchronized(lock) { writable(expected); buffer.undo(); changed() }
    fun redo(expected: String? = null) = synchronized(lock) { writable(expected); buffer.redo(); changed() }
    fun jump(index: Int, expected: String? = null) = synchronized(lock) {
        writable(expected); require(index in buffer.strokeRecords.indices) { "Paint stroke index is out of range" }
        buffer.jumpToStroke(index); changed()
    }
    fun cancel(expected: String? = null) = synchronized(lock) {
        expected?.let { if (it != token()) throw WorkspaceConflict(it, token()) }
        if (committing) throw WorkspaceBusy()
        if (!closed) { buffer.discard(); closed = true; active = false; changed() }
    }
    /** UI projection may be retired during a successful pre-CAS projection without cancelling its commit. */
    fun dismiss() = synchronized(lock) { if (!committing) cancel() }
    internal fun freeze(expected: String, rebuildMesh: Boolean, preserve: Boolean, checkpoint: () -> Unit): WorkspacePaintRaster = synchronized(lock) {
        writable(expected); committing = true
        try { WorkspacePaintRaster.capture(layerId, buffer.workingImage, rebuildMesh, preserve, checkpoint) }
        catch (failure: Throwable) { committing = false; throw failure }
    }
    internal fun finish(result: WorkspaceMutationResult) = synchronized(lock) {
        completion = result; committing = false; closed = true; changed()
    }
    internal fun retry() = synchronized(lock) { committing = false }
    internal fun completed(): WorkspaceMutationResult? = synchronized(lock) { completion }
    fun snapshot(): JsonObject = synchronized(lock) { buildJsonObject {
        put("session_id", id); put("session_state", token()); put("project_id", projectId)
        put("workspace_state", workspaceState); put("layer_id", layerId); put("layer_name", layerName)
        put("width", width); put("height", height); put("index", buffer.currentStrokeIndex)
        put("dirty", buffer.isDirty); put("active_stroke", active); put("closed", closed)
        putJsonArray("strokes") { buffer.strokeRecords.forEach { record ->
            add(buildJsonObject { put("id", record.id); put("name", record.name) })
        } }
    } }
    companion object {
        private fun copy(image: BufferedImage) = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), 0, image.width)
        }
    }
}

/** Process resources are captured from committed document/model once; only commit enters history. */
internal class WorkspacePaintSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val sessions = ConcurrentHashMap<String, WorkspacePaintSession>()
    private val commands = WorkspaceRasterCommands(runtime)
    fun begin(state: String, layerId: String): WorkspacePaintSession = synchronized(sessions) {
        val captured = runtime.capture()
        if (captured.state != state) throw WorkspaceConflict(state, captured.state)
        val layer = RasterPaintCommit.sourceLayerFor(captured.model, captured.model.analysis, layerId)
            ?: throw IllegalArgumentException("Paint layer not found: $layerId")
        val image = captured.document.sourceLayerImage(layer.id.raw)
        require(sessions.values.count { !it.finished } < 32) { "Close a paint session before opening another" }
        sessions.entries.removeIf { it.value.finished }
        val session = WorkspacePaintSession(UUID.randomUUID().toString(), captured.projectId, state,
            layerId, layer.name, image) {
            val now = runtime.capture()
            if (now.projectId != captured.projectId || now.state != state) throw WorkspaceConflict(state, now.state)
        }
        sessions[session.id] = session
        session
    }
    fun get(id: String): WorkspacePaintSession = sessions[id] ?: throw IllegalArgumentException("Paint session not found: $id")
    fun list(): List<JsonObject> = sessions.values.map { it.snapshot() }.sortedBy { it.getValue("session_id").jsonPrimitive.content }
    fun clear() { sessions.values.forEach { runCatching { it.cancel() } }; sessions.clear() }
    suspend fun commit(id: String, sessionState: String, rebuildMesh: Boolean, preserve: Boolean, author: MutationAuthor,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceRasterCommit {
        val session = get(id)
        session.completed()?.let { return WorkspaceRasterCommit(WorkspaceCommit(runtime.capture(), false), it) }
        val context = currentCoroutineContext()
        context.ensureActive()
        val raster = session.freeze(sessionState, rebuildMesh, preserve) { context.ensureActive() }
        try {
            val result = commands.commitRaster(session.projectId, session.workspaceState, raster,
                "Painted source layer "+session.layerId, author, beforeCommit)
            session.finish(result.mutation)
            currentCoroutineContext()[WorkspacePaintJobExecution]?.committed(result.mutation.compact())
            return result
        } catch (failure: Throwable) { session.retry(); throw failure }
    }
}

internal class WorkspacePaintJobExecution(private val completion: WorkspaceJobCompletion) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspacePaintJobExecution>
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
