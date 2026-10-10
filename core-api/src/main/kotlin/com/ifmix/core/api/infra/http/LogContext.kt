package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC

/**
 * 请求级日志上下文：把 [ActionContext.logFields] 逐项写入 MDC（每个字段一个 key，值为 null 的不写），
 * 日志是文本格式（logback-spring.xml 的 LOG_PATTERN），`%mdc` 把每项输出成 `rid=… pid=…`。
 * 加字段改 [ActionContext.logFields] 即可，不用动这里和 logback 配置。
 *
 * MDC 只对当前线程生效。DGS 开虚拟线程后 data fetcher 与 servlet filter 不在同一线程，
 * 所以 [bind] 同时把 ctx 挂到 request attribute 上，filter 等其他线程用 [bindFrom] 取回再绑。
 * 提交到虚拟线程/线程池的任务：用 [snapshot] + [runWith] 把上下文带过去（见文档注释）。
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

    /**
     * 捕获当前线程 MDC 快照，用于**跨线程**传递上下文。
     *
     * 虚拟线程（ThreadPerTaskExecutor）与线程池都**不继承**父线程的 MDC（ThreadLocal 不传），
     * 所以异步任务里的日志默认一个上下文字段都没有。提交任务处调一次，任务内用 [runWith] 恢复。
     */
    fun snapshot(): Map<String, String>? = MDC.getCopyOfContextMap()

    /**
     * 在任务线程恢复 [snapshot] 后执行 [block]，结束时还原（不是简单 clear——复用线程池时
     * 不还原会污染下一个任务）。
     *
     * 用法：
     * ```kotlin
     * val snap = LogContext.snapshot()               // 提交线程（servlet）有 rid
     * executor.execute { LogContext.runWith(snap) { doWork() } }
     * ```
     *
     * 注意：异步写盘不丢上下文——`LoggingEvent` 在**构造时**就复制了 MDC，
     * 所以 logback 的 AsyncAppender 即使在别的线程编码也拿得到。
     */
    fun <T> runWith(snapshot: Map<String, String>?, block: () -> T): T {
        val prev = MDC.getCopyOfContextMap()
        if (snapshot != null) MDC.setContextMap(snapshot)
        return try {
            block()
        } finally {
            if (prev != null) MDC.setContextMap(prev) else clear()
        }
    }
}
