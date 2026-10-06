package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GraphQlErrorBodyTest {

    @Test
    fun `error builds extensions with code and errorName`() {
        val body = GraphQlErrorBody.error("400003", "WIRE_DECRYPT_FAILED", "bad encrypted payload")
        assertThat(body.errors.size).isEqualTo(1)
        assertThat(body.errors[0].message).isEqualTo("bad encrypted payload")
        assertThat(body.errors[0].extensions).isEqualTo(mapOf("code" to "400003", "errorName" to "WIRE_DECRYPT_FAILED"))
    }

    @Test
    fun `null extras are dropped from extensions`() {
        val body = GraphQlErrorBody.error("429000", "RATE_LIMITED", "too many", mapOf("retryAfterSec" to 30L, "details" to null))
        assertThat(body.errors[0].extensions).isEqualTo(mapOf("code" to "429000", "errorName" to "RATE_LIMITED", "retryAfterSec" to 30L))
    }
}
