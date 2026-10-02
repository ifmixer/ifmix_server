package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest
import com.ifmix.core.api.infra.codec.toBase58
import org.slf4j.MDC

/**
 * 请求级日志上下文：把 [ActionContext.logFields] 拼成一个 MDC 值（key=[KEY]），
 * logback pattern 用 `%X{ctx}` 输出，于是该请求线程上的所有日志都自动带上 iid/cid/ip/bot 等字段。
 * 加字段改 [ActionContext.logFields] 即可，不用动这里和 pattern。
 *
 * MDC 只对当前线程生效。DGS 开虚拟线程后 data fetcher 与 servlet filter 不在同一线程，
 * 所以 [bind] 同时把 ctx 挂到 request attribute 上，filter 等其他线程用 [bindFrom] 取回再绑。
 * @Async / 线程池里的日志不带（需要时在提交任务处自行 bind）。
 */
object LogContext {
    const val KEY = "ctx"
    private const val ATTR = "com.ifmix.logContext.actionContext"
    private const val ATTR_REQUEST_ID = "com.ifmix.logContext.requestId"

    /**
     * 请求入口（filter）调用：生成 reqId 存到 request 上，并先把 `rid=…` 绑到当前线程，
     * 保证构造出 ActionContext 之前的日志也有 rid。返回 reqId 供写响应头。
     */
    fun start(request: HttpServletRequest): String {
        val rid = com.ifmix.core.api.infra.db.UuidV7.generate().toBase58()
        request.setAttribute(ATTR_REQUEST_ID, rid)
        MDC.put(KEY, "rid=$rid")
        return rid
    }

    /** 本请求的 reqId（filter 未经过时为 null）。 */
    fun requestId(request: HttpServletRequest): String? = request.getAttribute(ATTR_REQUEST_ID) as? String

    fun bind(ctx: ActionContext, request: HttpServletRequest? = null) {
        request?.setAttribute(ATTR, ctx)
        MDC.put(KEY, ctx.logFields().entries.joinToString(" ") { (k, v) -> "$k=${v ?: "-"}" })
    }

    /** 从 request 上取回本请求最近一次构造的 ctx 并绑到当前线程。返回是否取到（没构造过 ctx 时 false，不动 MDC）。 */
    fun bindFrom(request: HttpServletRequest): Boolean {
        val ctx = request.getAttribute(ATTR) as? ActionContext ?: return false
        bind(ctx)
        return true
    }

    fun clear() = MDC.remove(KEY)
}
