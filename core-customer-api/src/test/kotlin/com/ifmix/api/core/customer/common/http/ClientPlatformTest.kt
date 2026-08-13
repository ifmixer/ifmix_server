package com.ifmix.api.core.common.infra.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClientPlatformTest {

    @Test
    fun parsesValueCaseInsensitively() {
        assertThat(ClientPlatform.fromHeader("ios")).isEqualTo(ClientPlatform.IOS)
        assertThat(ClientPlatform.fromHeader("ANDROID")).isEqualTo(ClientPlatform.ANDROID)
    }

    @Test
    fun nullOrBlankReturnsNull() {
        assertThat(ClientPlatform.fromHeader(null)).isNull()
        assertThat(ClientPlatform.fromHeader("  ")).isNull()
    }

    @Test
    fun invalidValueThrows() {
        assertThatThrownBy { ClientPlatform.fromHeader("windows") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
