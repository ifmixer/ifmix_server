package com.ifmix.core.api.infra.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnvelopeTest {

    @Test
    fun okWrapsData() {
        val env = Envelope.ok("hello")
        assertThat(env.code).isEqualTo("200000")
        assertThat(env.msg).isEqualTo("success")
        assertThat(env.data).isEqualTo("hello")
    }

    @Test
    fun errorHasNullData() {
        val env = Envelope.error("404000", "not found")
        assertThat(env.code).isEqualTo("404000")
        assertThat(env.msg).isEqualTo("not found")
        assertThat(env.data as Any?).isNull()
    }
}
