package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument

/** One number gesture always prepares from its captured model and commits one complete operation. */
internal class WarpControlField(val expectedState: String, val state: PSD2LiveState,
                               val document: WorkspaceDocument, val model: RigPreviewModel,
                               var operation: WorkspaceDocumentOperation, var preview: RigPreviewModel? = null)
