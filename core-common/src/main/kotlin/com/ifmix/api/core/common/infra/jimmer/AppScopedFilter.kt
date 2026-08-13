package com.ifmix.api.core.common.infra.jimmer

import com.ifmix.api.core.common.infra.http.OperationContext

/**
 * OperationContext holder for thread-local storage during request processing.
 * Used by controllers to propagate appId and other context to services/repositories.
 */
object OperationContextHolder {
    private val holder = ThreadLocal<OperationContext>()

    fun set(ctx: OperationContext) = holder.set(ctx)
    fun current(): OperationContext = holder.get()
        ?: throw IllegalStateException("No OperationContext in current thread")
    fun clear() = holder.remove()
}

// Note: AppScopedFilter implementation will be added after verifying correct Jimmer API usage.
// For now, tenant isolation is handled manually in repositories/services.
