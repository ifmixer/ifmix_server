package com.ifmix.core.api.infra.http

/**
 * 统一错误响应体：与 GraphQL 执行错误同形状（`errors[].extensions.code`）。
 *
 * 供 GraphQL 引擎之外的边缘错误路径复用（GlobalExceptionHandler、WireCryptoFilter 解密失败等），
 * 客户端对全站错误只有一个解码点：读 `errors[0].extensions.code`（六位码），HTTP status 与前三位一致。
 * 请求级错误不带 `data` 键（GraphQL 规范：执行前失败不出现 data），与 Spring GraphQL 自身的
 * malformed-request 响应一致。
 */
data class GraphQlErrorBody(val errors: List<Error>) {
    data class Error(val message: String, val extensions: Map<String, Any?>)

    companion object {
        /** [extra] 进 extensions（retryAfterSec/details 等），null 值丢弃。 */
        fun error(code: String, errorName: String, msg: String, extra: Map<String, Any?> = emptyMap()): GraphQlErrorBody =
            GraphQlErrorBody(listOf(Error(msg, linkedMapOf("code" to code, "errorName" to errorName) + extra.filter { it.value != null })))
    }
}
