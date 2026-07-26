package com.ifmix.api.core.common.http

import com.ifmix.api.core.modules.todo.TodoDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 验证 application.yml 的 Jackson 配置：Instant 序列化成 epoch 毫秒（整数），而非 ISO 串或秒.纳秒。
 * @JsonTest 只装配 Jackson，无需 Mongo/Docker。
 */
@JsonTest
class InstantJsonSerializationTest {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun instantSerializesAsEpochMillis() {
        val dto = TodoDto(
            id = "id1",
            title = "t",
            done = false,
            items = emptyList(),
            createdAt = Instant.ofEpochMilli(1234567890123),
            updatedAt = null,
        )

        val json = objectMapper.writeValueAsString(dto)

        assertThat(json).contains("\"createdAt\":1234567890123")
        assertThat(json).contains("\"updatedAt\":null")
    }
}
