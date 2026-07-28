package com.ifmix.api.core.common.jimmer.filter

import com.ifmix.api.core.common.http.RequestContext

/**
 * RequestContext holder for thread-local storage during request processing.
 * Used by controllers to propagate appId and other context to services/repositories.
 */
object RequestContextHolder {
    private val holder = ThreadLocal<RequestContext>()

    fun set(ctx: RequestContext) = holder.set(ctx)
    fun current(): RequestContext = holder.get()
        ?: throw IllegalStateException("No RequestContext in current thread")
    fun clear() = holder.remove()
}

// Note: AppScopedFilter implementation will be added after verifying correct Jimmer API usage.
// For now, tenant isolation is handled manually in repositories/services.
