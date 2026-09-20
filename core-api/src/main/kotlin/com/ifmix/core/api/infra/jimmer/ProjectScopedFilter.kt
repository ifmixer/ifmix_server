package com.ifmix.core.api.infra.jimmer

import com.ifmix.core.api.infra.http.ActionContext

/**
 * ActionContext holder for thread-local storage during request processing.
 * Used by controllers to propagate projectId and other context to services/repositories.
 */
object ActionContextHolder {
    private val holder = ThreadLocal<ActionContext>()

    fun set(ctx: ActionContext) = holder.set(ctx)
    fun current(): ActionContext = holder.get()
        ?: throw IllegalStateException("No ActionContext in current thread")
    fun clear() = holder.remove()
}

// Note: ProjectScopedFilter implementation will be added after verifying correct Jimmer API usage.
// For now, tenant isolation is handled manually in repositories/services.
