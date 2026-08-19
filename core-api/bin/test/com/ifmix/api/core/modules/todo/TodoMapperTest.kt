package com.ifmix.api.core.modules.todo

import io.mcarle.konvert.api.Konverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TodoMapperTest {

    private val mapper: TodoMapper = Konverter.get()

    @Test
    fun mapsEntityToResponseWithEpochMillis() {
        val doc = TodoEntity().apply {
            id = "aaaaaaaaaaaaaaaaaaaaaaaa"
            title = "t"
            done = true
            createdAt = Instant.ofEpochMilli(1000)
            updatedAt = Instant.ofEpochMilli(2000)
            items = mutableListOf(TodoItem(id = "i1", content = "c", done = false))
        }

        val r = mapper.toDto(doc)

        assertThat(r.id).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(r.title).isEqualTo("t")
        assertThat(r.done).isTrue()
        assertThat(r.createdAt).isEqualTo(Instant.ofEpochMilli(1000))
        assertThat(r.updatedAt).isEqualTo(Instant.ofEpochMilli(2000))

        val item = r.items.single()
        assertThat(item.id).isEqualTo("i1")
        assertThat(item.content).isEqualTo("c")
        assertThat(item.done).isFalse()
    }

    @Test
    fun nullTimestampsStayNull() {
        val doc = TodoEntity().apply {
            id = "bbbbbbbbbbbbbbbbbbbbbbbb"
            title = "t"
        }
        val r = mapper.toDto(doc)
        assertThat(r.createdAt).isNull()
        assertThat(r.updatedAt).isNull()
        assertThat(r.items).isEmpty()
    }
}
