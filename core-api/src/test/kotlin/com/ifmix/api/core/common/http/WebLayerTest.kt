package com.ifmix.api.core.infra.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

class WebLayerTest {

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(TestController())
            .setControllerAdvice(GlobalExceptionHandler(true), EnvelopeResponseAdvice())
            .build()
    }

    @Test
    fun successResponseIsWrappedInEnvelope() {
        mvc.perform(get("/_test/ok"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("200000"))
            .andExpect(jsonPath("$.msg").value("success"))
            .andExpect(jsonPath("$.data.k").value("v"))
    }

    @Test
    fun apiErrorMapsToEnvelopeWithStatus() {
        mvc.perform(get("/_test/boom"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("404000"))
            .andExpect(jsonPath("$.msg").value("gone"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun validationErrorHasFieldMessage() {
        mvc.perform(
            post("/_test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("name:")))
    }

    @Test
    fun genericExceptionMapsTo500() {
        mvc.perform(get("/_test/rte"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("500000"))
            .andExpect(jsonPath("$.msg").value("kaboom"))
    }

    @RestController
    class TestController {
        @GetMapping("/_test/ok")
        fun ok(): Map<String, String> = mapOf("k" to "v")

        @GetMapping("/_test/boom")
        fun boom(): Map<String, String> = throw ApiError(ErrorCode.NOT_FOUND, "gone")

        @GetMapping("/_test/rte")
        fun rte(): Map<String, String> = throw RuntimeException("kaboom")

        @PostMapping("/_test/validate")
        fun validate(@Valid @RequestBody p: Payload): Map<String, String> = mapOf("ok" to (p.name ?: ""))

        data class Payload(@field:NotBlank val name: String? = null)
    }
}
