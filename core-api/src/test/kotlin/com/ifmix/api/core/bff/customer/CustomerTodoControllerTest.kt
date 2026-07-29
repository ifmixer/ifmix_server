package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.RequestHeaders
import com.ifmix.api.core.support.AbstractMongoTest
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
class CustomerTodoControllerTest : AbstractMongoTest() {

    @Autowired
    private lateinit var wac: WebApplicationContext

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    private fun createTodo(body: String): String {
        val resp = mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("200000"))
            .andReturn().response.contentAsString
        return JsonPath.read(resp, "$.data.id")
    }

    @Test
    fun createThenGetByIdReturnsEnvelope() {
        val id = createTodo("""{"title":"shopping","items":[{"content":"milk"}]}""")
        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("shopping"))
            .andExpect(jsonPath("$.data.items[0].content").value("milk"))
            .andExpect(jsonPath("$.data.items[0].id").isNotEmpty)
            .andExpect(jsonPath("$.data.createdAt").isNumber)
    }

    @Test
    fun missingAppIdHeaderRejected() {
        mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"x"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun blankTitleFailsValidation() {
        mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("title")))
    }

    @Test
    fun tenantIsolationHidesOtherAppTodo() {
        val id = createTodo("""{"title":"secret"}""")
        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, OTHER_APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("404000"))
    }

    @Test
    fun deleteSoftDeletesAndSubsequentGetIs404() {
        val id = createTodo("""{"title":"t"}""")
        mvc.perform(
            post("/customer/core/mutation/todo/deleteById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deleted").value(true))

        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun findByCursorReturnsPageEnvelope() {
        createTodo("""{"title":"a"}""")
        createTodo("""{"title":"b"}""")
        mvc.perform(
            put("/customer/core/query/todo/findByCursor")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"limit":10}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.hasMore").value(false))
    }

    companion object {
        private const val APP_ID = "0123456789abcdef01234567"
        private const val OTHER_APP_ID = "ffffffffffffffffffffffff"
    }
}
