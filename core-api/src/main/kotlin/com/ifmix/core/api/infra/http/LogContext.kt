package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC

/**
 * 请求级日志上下文：把 [ActionContext.logFields] 逐项写入 MDC（每个字段一个 key，值为 null 的不写），
 * 文件日志是 JSON（logback-spring.xml 的 StructuredLogEncoder，MDC 每项成为一个顶层字段），
 * 本地控制台用 `%mdc` 文本输出。加字段改 [ActionContext.logFields] 即可，不用动这里和 logback 配置。
 *
 * MDC 只对当前线程生效。DGS 开虚拟线程后 data fetcher 与 servlet filter 不在同一线程，
 * 所以 [bind] 同时把 ctx 挂到 request attribute 上，filter 等其他线程用 [bindFrom] 取回再绑。
 * @Async / 线程池里的日志不带（需要时在提交任务处自行 bind）。
 */
object LogContext {
    /** 曾写入过的 MDC key（clear 时逐个移除，不用 MDC.clear() 以免误删第三方放的值）。 */
    private val KEYS = ActionContext().logFields().keys
    private const val ATTR = "com.ifmix.logContext.actionContext"
    private const val ATTR_REQUEST_ID = "com.ifmix.logContext.requestId"

    /**
     * 请求入口（filter）调用：reqId 由调用方从 meta 取出传入（RequestParser.parseMeta().reqId，
     * 已做控制字符/限长清洗），没有则生成 UuidV7；`rid=…` 绑到当前线程，保证构造出 ActionContext
     * 之前的日志也有 rid。返回 reqId 供日志/信封回传。
     */
    fun start(request: HttpServletRequest, reqId: String?): String {
        val rid = reqId?.takeIf { it.isNotEmpty() }
            ?: com.ifmix.core.api.infra.db.UuidV7.generate().toString()
        request.setAttribute(ATTR_REQUEST_ID, rid)
        MDC.put("rid", rid)
        return rid
    }


    /** 本请求的 reqId（filter 未经过时为 null）。 */
    fun requestId(request: HttpServletRequest): String? = request.getAttribute(ATTR_REQUEST_ID) as? String

    fun bind(ctx: ActionContext, request: HttpServletRequest? = null) {
        request?.setAttribute(ATTR, ctx)
        for ((k, v) in ctx.logFields()) if (v != null) MDC.put(k, v.toString()) else MDC.remove(k)
    }

    /** 从 request 上取回本请求最近一次构造的 ctx 并绑到当前线程。返回是否取到（没构造过 ctx 时 false，不动 MDC）。 */
    fun bindFrom(request: HttpServletRequest): Boolean {
        val ctx = request.getAttribute(ATTR) as? ActionContext ?: return false
        bind(ctx)
        return true
    }

    fun clear() = KEYS.forEach(MDC::remove)
}
