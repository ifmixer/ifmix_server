package com.ifmix.core.api.infra.http

/**
 * RPC 信封 body（wire 解密后到达）：`{"meta": {...}, "input": {...}}`。
 *
 * `input` 的具体类型由各 endpoint 签名的泛型实参声明（如 `ApiRequestBody<FindTodoByIdInput>`），
 * Spring `@RequestBody` 按参数完整泛型在**边界处**反序列化——非法 input 抛
 * [org.springframework.http.converter.HttpMessageNotReadableException]，由
 * [GlobalExceptionHandler] 映射 400000，controller 内不再手工 `objectMapper.convertValue`。
 * springdoc 亦按该泛型生成各 action 的 input schema（R4 客户端 OpenAPI codegen 的前提）。
 *
 * @param meta 请求元信息；缺失视为全空 meta（容错：明文 curl 调试场景）。
 * @param input action 输入 payload；缺段/显式 null 为 null，必填的 action 用 [requireInput] 收口。
 */
data class ApiRequestBody<T>(
    val meta: RequestMeta? = null,
    val input: T? = null,
)

/** 无入参 action（`q_auth_session_me` / `m_auth_account_deleteOne` 等）的 input 占位。 */
object NoInput

/** input 必填的 action 用：缺段/显式 null → 400000。input 整段可空的语义（如 cs list）直接读 `body.input`。 */
fun <T> ApiRequestBody<T>.requireInput(): T =
    input ?: throw ApiError(ErrorCode.INVALID_REQUEST, "missing input")
