package com.ifmix.core.api.infra.http

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RpcOpenApiErrorCustomizer] 单测：/api/ 前缀 operation 补通用错误响应（不覆盖已声明状态），
 * 非 /api/ 路径不动；ErrorEnvelope 组件 schema 注册且字段/可空性正确。
 */
class RpcOpenApiErrorCustomizerTest {

    private fun openApiWithApiAndWebhook(): OpenAPI {
        val rpcOp = Operation().responses(
            ApiResponses().addApiResponse("200", ApiResponse().description("ok")),
        )
        val webhookOp = Operation() // 无 responses
        return OpenAPI().paths(
            Paths()
                .addPathItem("/api/customer/core/q_demo_todo_getById", PathItem().post(rpcOp))
                .addPathItem("/webhook/payment/callback", PathItem().post(webhookOp)),
        )
    }

    @Test
    fun `adds generic error envelope responses to api operations only`() {
        val openApi = openApiWithApiAndWebhook()
        RpcOpenApiErrorCustomizer().customise(openApi)

        val op = openApi.paths!!["/api/customer/core/q_demo_todo_getById"]!!.post!!
        assertEquals("ok", op.responses!!["200"]!!.description) // 已声明响应不动
        for (status in listOf("400", "401", "403", "404", "429", "500")) {
            val resp = op.responses!![status]
            assertNotNull(resp, "missing $status")
            val ref = resp!!.content!!["application/json"]!!.schema!!.`$ref`
            assertEquals("#/components/schemas/ErrorEnvelope", ref)
        }
        val resp401 = op.responses!!["401"]!!
        assertTrue(resp401.description!!.contains("401000") && resp401.description!!.contains("401002"))

        // 非 /api/ 路径不加（responses 保持 null/空）
        assertNull(openApi.paths!!["/webhook/payment/callback"]!!.post!!.responses)
    }

    @Test
    fun `registers error envelope component schema once with correct shape`() {
        val openApi = openApiWithApiAndWebhook()
        val customizer = RpcOpenApiErrorCustomizer()
        customizer.customise(openApi)
        customizer.customise(openApi) // 二次执行不重复注册/不改形状

        val schema = openApi.components!!.schemas!![RpcOpenApiErrorCustomizer.ERROR_ENVELOPE_SCHEMA]!!
        assertEquals(setOf("code", "msg", "data", "reqId"), schema.properties!!.keys)
        assertEquals(true, schema.properties!!["data"]!!.nullable)
        assertEquals(true, schema.properties!!["reqId"]!!.nullable)
        assertEquals("400000", schema.properties!!["code"]!!.default)
    }
}
