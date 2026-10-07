package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.infra.http.ActionContext
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.context.DgsCustomContextBuilderWithRequest
import org.dataloader.BatchLoaderEnvironment
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.context.request.WebRequest

/**
 * 单请求 ActionContext 容器（DGS custom context，随 [DgsContext] 进 GraphQLContext）。
 *
 * 为什么不能靠 ThreadLocal：DGS 虚拟线程模式下每个 fetcher / DataLoader 调度到独立线程，
 * ThreadLocal 经 micrometer ContextSnapshot 的传播不可靠（嵌套 resolver 实测拿不到）。
 * DgsContext 经 dfe / BatchLoaderEnvironment 传递，与线程无关——是跨线程取「原上下文」的权威通道。
 *
 * 语义：首次 [ActionContextProvider.fromDfe] 构建后写入 [actionContext]，同请求后续 fromDfe
 * 一律复用——嵌套 resolver 拿到的是顶层构建的原 ctx（isMutation / preferReader / actionName
 * 保持原值）。若在嵌套处重建，parent type 不是 Mutation → isMutation=false → preferReader=true，
 * mutation 流程内的读会误走 reader 池（ModuleCtxFactory 按 preferReader 路由 writer/reader）。
 */
class RequestActionContext {
    var actionContext: ActionContext? = null

    companion object {
        /** 从 fetcher 的 dfe 取（无 DgsContext / 类型不符 → null，测试与批处理等场景兜底）。 */
        fun fromDfe(dfe: com.netflix.graphql.dgs.DgsDataFetchingEnvironment): RequestActionContext? =
            runCatching { DgsContext.getCustomContext<RequestActionContext>(dfe) }.getOrNull()

        /** 从 DataLoader 的 BatchLoaderEnvironment 取（要求 WithContext 变体才有 env）。 */
        fun fromBatchLoader(env: BatchLoaderEnvironment): RequestActionContext? =
            runCatching { DgsContext.getCustomContext<RequestActionContext>(env) }.getOrNull()
    }
}

/** 每请求构建一个空容器；真正的 ActionContext 由首个 [ActionContextProvider.fromDfe] 惰性填充。 */
@Component
class RequestActionContextBuilder : DgsCustomContextBuilderWithRequest<RequestActionContext> {
    override fun build(
        extensions: Map<String, Any>?,
        headers: HttpHeaders?,
        webRequest: WebRequest?,
    ): RequestActionContext = RequestActionContext()
}
