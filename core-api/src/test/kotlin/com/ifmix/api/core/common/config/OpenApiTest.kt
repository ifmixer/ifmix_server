package com.ifmix.api.core.infra.config

import com.ifmix.api.core.support.AbstractMongoTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
class OpenApiTest : AbstractMongoTest() {

    @Autowired
    private lateinit var wac: WebApplicationContext

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    @Test
    fun customerGroupDocExposesTodoPathAndSecurityScheme() {
        mvc.perform(get("/v3/api-docs/customer"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/customer/core/mutation/todo/createOne']").exists())
            .andExpect(jsonPath("$.components.securitySchemes.appId.name").value("x-app-id"))
    }
}
