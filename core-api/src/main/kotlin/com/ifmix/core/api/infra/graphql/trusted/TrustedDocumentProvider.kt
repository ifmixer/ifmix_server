package com.ifmix.core.api.infra.graphql.trusted

import graphql.ExecutionInput
import graphql.GraphqlErrorBuilder
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * Trusted Document 执行入口（graphql-java [PreparsedDocumentProvider]）。
 *
 * trusted-documents 机制**恒定开启**：GReq 请求（path 带 reqName）一律走 allowlist（命中执行、未命中拒绝）。
 * [allowRawQuery] 仅控制「未带 reqName（走 GQL raw 入口）时是否允许回退 body raw query」：
 *  - true（本地/dev）→ 回退 raw query，供 GraphiQL/API 探索。
 *  - false（uat/prod）→ 拒绝，强制所有请求走 persisted query。
 *
 * 流程（不碰 body）：
 *  1. 从 GraphQLContext 取 [ReqNamePathInterceptor.CTX_REQ_NAME]（来自 GReq path 末段）。
 *  2. 有 reqName：命中 allowlist → 返回预解析 Document；未命中 → 拒绝（无论 allowRawQuery）。
 *  3. 无 reqName：allowRawQuery=true → 回退 raw query；false → 拒绝。
 *
 * bff 当前固定 customer（单端点）；未来多 BFF 时从 context/path 区分。
 */
class TrustedDocumentProvider(
    private val store: PersistedQueryStore,
    private val allowRawQuery: Boolean,
    private val bff: String = "customer",
) : PreparsedDocumentProvider {

    private val log = LoggerFactory.getLogger(TrustedDocumentProvider::class.java)

    override fun getDocumentAsync(
        executionInput: ExecutionInput,
        parseAndValidate: Function<ExecutionInput, PreparsedDocumentEntry>,
    ): CompletableFuture<PreparsedDocumentEntry> {
        val reqName = executionInput.graphQLContext.get<String?>(ReqNamePathInterceptor.CTX_REQ_NAME)

        if (!reqName.isNullOrBlank()) {
            val entry = store.getByReqName(reqName, bff)
            if (entry != null) {
                return CompletableFuture.completedFuture(PreparsedDocumentEntry(entry.document))
            }
            // 有 reqName 但不在白名单 → 一律拒绝
            log.warn("trusted-documents: unknown reqName={} bff={}", reqName, bff)
            return CompletableFuture.completedFuture(reject("unknown req-name: $reqName"))
        }

        // 无 reqName（走 GQL raw 入口）
        if (!allowRawQuery) {
            log.warn("trusted-documents: request without reqName rejected (allow-raw-query=false) bff={}", bff)
            return CompletableFuture.completedFuture(reject("persisted query reqName is required"))
        }

        // 本地：回退 raw query（body 里的 query），供 API 探索
        return CompletableFuture.completedFuture(parseAndValidate.apply(executionInput))
    }

    private fun reject(message: String): PreparsedDocumentEntry =
        PreparsedDocumentEntry(
            GraphqlErrorBuilder.newError()
                .message(message)
                .extensions(mapOf("code" to "403000", "errorName" to "FORBIDDEN"))
                .build(),
        )
}
