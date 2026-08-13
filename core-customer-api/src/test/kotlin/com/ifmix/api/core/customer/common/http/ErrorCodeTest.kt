package com.ifmix.api.core.common.infra.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorCodeTest {

    @Test
    fun notFoundMapsTo404() {
        assertThat(ErrorCode.NOT_FOUND.externalCode).isEqualTo("404000")
        assertThat(ErrorCode.NOT_FOUND.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun invalidRequestMapsTo400() {
        assertThat(ErrorCode.INVALID_REQUEST.externalCode).isEqualTo("400000")
        assertThat(ErrorCode.INVALID_REQUEST.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
