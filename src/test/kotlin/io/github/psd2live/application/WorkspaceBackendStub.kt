package io.github.psd2live.application

import java.lang.reflect.Proxy

/** Only contract-test fixtures may omit capabilities; an unexpected call fails at its exact port. */
internal abstract class WorkspaceBackendStub : WorkspaceBackend by unusedPorts() {
    // Small contract fixtures supply their own deterministic query values.
    override fun captureQueries(): WorkspaceQueries = this
}

internal abstract class WorkspaceObservationStub : WorkspaceObservation by unusedObservation()

private fun unusedObservation(): WorkspaceObservation = Proxy.newProxyInstance(
    WorkspaceObservation::class.java.classLoader, arrayOf(WorkspaceObservation::class.java),
) { _, method, _ -> throw AssertionError("Unexpected observation call: ${method.name}") } as WorkspaceObservation

private fun unusedPorts(): WorkspaceBackend = Proxy.newProxyInstance(
    WorkspaceBackend::class.java.classLoader, arrayOf(WorkspaceBackend::class.java),
) { _, method, _ -> throw AssertionError("Unexpected workspace port call: ${method.declaringClass.simpleName}.${method.name}") } as WorkspaceBackend
