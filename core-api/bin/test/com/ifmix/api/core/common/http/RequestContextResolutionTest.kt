package com.ifmix.api.core.common.http

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class RequestContextResolutionTest {

    private val validAppId = "0123456789abcdef01234567"
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(CtxController())
            .setCustomArgumentResolvers(RequestContextArgumentResolver())
            .addInterceptors(HeaderValidationInterceptor())
            .setControllerAdvice(GlobalExceptionHandler(true), EnvelopeResponseAdvice())
            .build()
    }

    @Test
    fun resolvesContextFromHeaders() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, validAppId)
                .header(RequestHeaders.LANG, "en")
                .header(RequestHeaders.CLIENT_PLATFORM, "ios")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.appId").value(validAppId))
            .andExpect(jsonPath("$.data.lang").value("en"))
            .andExpect(jsonPath("$.data.platform").value("IOS"))
    }

    @Test
    fun missingAppIdReturns400() {
        mvc.perform(get("/customer/core/query/ctx/echo"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun invalidAppIdReturns400() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, "not-an-objectid")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun invalidPlatformReturns400() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, validAppId)
                .header(RequestHeaders.CLIENT_PLATFORM, "windows")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-client-platform")))
    }

    @RestController
    class CtxController {
        @GetMapping("/customer/core/query/ctx/echo")
        fun echo(ctx: RequestContext): Map<String, Any?> = mapOf(
            "appId" to ctx.appId,
            "lang" to ctx.lang,
            "platform" to ctx.clientPlatform?.name,
        )
    }
}
