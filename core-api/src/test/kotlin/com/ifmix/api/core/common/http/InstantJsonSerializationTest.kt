package com.ifmix.api.core.infra.http

import com.ifmix.api.core.infra.config.JacksonConfig
import com.ifmix.api.core.service.todo.TodoDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 验证 JacksonConfig：Instant 序列化成 epoch 毫秒（整数），而非 ISO 串或秒.纳秒。
 * @JsonTest 只装配 Jackson，无需 Mongo/Docker。
 */
@JsonTest
@Import(JacksonConfig::class)
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

    @Test
    fun nullInstantSerializesAsNull() {
        val dto = TodoDto(
            id = "id2",
            title = "t",
            done = true,
            items = emptyList(),
            createdAt = null,
            updatedAt = null,
        )

        val json = objectMapper.writeValueAsString(dto)
        assertThat(json).contains("\"createdAt\":null")
    }

    @Test
    fun deserializesEpochMillisToInstant() {
        val dto = objectMapper.readValue("""{"id":"x","title":"t","done":false,"items":[],"createdAt":1700000000000,"updatedAt":null}""", TodoDto::class.java)
        assertThat(dto.createdAt).isEqualTo(Instant.ofEpochMilli(1700000000000))
    }
}
