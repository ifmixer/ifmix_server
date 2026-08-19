package com.ifmix.api.core.e2e

import com.ifmix.api.core.e2e.support.E2eTestBase
import com.ifmix.api.core.infra.db.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.http.MediaType
import java.util.UUID

/**
 * Demo Todo 模块 E2E 测试 — 验证 MyBatis 访问层正常工作。
 */
@DisplayName("Demo Todo MyBatis E2E")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DemoTodoE2eTest : E2eTestBase() {

    private val appId = UUID.fromString(TEST_APP_ID)

    @Test
    @Order(1)
    fun `create and find todo`() {
        val todoId = UuidV7.generate()
        val createBody = """
            {
                "query": "mutation{mutation_demo_createTodo(input:{title:\"test\",done:false}){todo{id,title,done}}}",
                "variables": {}
            }
        """.trimIndent()

        val createResp = webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createBody)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.mutation_demo_create_todo.todo.id").value<String> { assertThat(it).isEqualTo(todoId.toString()) }
            .jsonPath("$.data.mutation_demo_create_todo.todo.title").value("test")
            .jsonPath("$.data.mutation_demo_create_todo.todo.done").value(false)
            .returnResult<String>()

        // 查询刚创建的 todo
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"{query_demo_findTodoById(id:\"$todoId\"){id,title,done}}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.query_demo_find_todo_by_id.id").value(todoId.toString())
            .jsonPath("$.data.query_demo_find_todo_by_id.title").value("test")
    }

    @Test
    @Order(2)
    fun `update todo`() {
        val todoId = UuidV7.generate()
        // 先创建
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_createTodo(input:{title:\"original\",done:false}){todo{id}}"}""")
            .exchange()
            .expectStatus().isOk

        // 更新
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_updateTodo(input:{id:\"$todoId\",set:{title:\"updated\"}}){success,/todo{id,title}}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.mutation_demo_update_todo.success").value(true)
            .jsonPath("$.data.mutation_demo_update_todo.todo.title").value("updated")
    }

    @Test
    @Order(3)
    fun `delete todo`() {
        val todoId = UuidV7.generate()
        // 先创建
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_createTodo(input:{title:\"to-delete\",done:true}){todo{id}}"}""")
            .exchange()
            .expectStatus().isOk

        // 删除
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_deleteTodo(id:\"$todoId\"){success}}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.mutation_demo_delete_todo.success").value(true)

        // 验证删除后查询返回 null
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"{query_demo_findTodoById(id:\"$todoId\")}"}""")
            .exchange()
            .expectStatus().is4xxClientError
    }

    @Test
    @Order(4)
    fun `find todos by cursor`() {
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"{query_demo_findTodosByCursor(input:{limit:10}){items{id,title,done}}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.query_demo_find_todos_by_cursor.items").isArray
    }

    @Test
    @Order(5)
    fun `batch delete todos`() {
        val id1 = UuidV7.generate()
        val id2 = UuidV7.generate()
        // 先创建两个 todo
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_createTodo(input:{title:\"batch1\",done:false}){todo{id}}} mutation{mutation_demo_createTodo(input:{title:\"batch2\",done:true}){todo{id}}}"}""")
            .exchange()
            .expectStatus().isOk

        // 批量删除
        webClient.post()
            .uri("/customer/graphql")
            .header("x-app-id", TEST_APP_ID)
            .header("x-install-id", TEST_INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"query":"mutation{mutation_demo_batchDeleteTodos(ids:[\"$id1\",\"$id2\"]){success}}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.mutation_demo_batch_delete_todos.success").value(true)
    }
}
