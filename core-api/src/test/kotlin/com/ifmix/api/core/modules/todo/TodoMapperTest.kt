package com.ifmix.api.core.modules.todo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TodoMapperTest {

    @Test
    fun mapsDocumentToResponseWithEpochMillis() {
        val doc = TodoDocument().apply {
            id = "aaaaaaaaaaaaaaaaaaaaaaaa"
            title = "t"
            done = true
            createdAt = Instant.ofEpochMilli(1000)
            updatedAt = Instant.ofEpochMilli(2000)
            items = mutableListOf(TodoItem(id = "i1", content = "c", done = false))
        }

        val r = TodoMapper.toResponse(doc)

        assertThat(r.id).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(r.title).isEqualTo("t")
        assertThat(r.done).isTrue()
        assertThat(r.createdAt).isEqualTo(1000L)
        assertThat(r.updatedAt).isEqualTo(2000L)

        val item = r.items.single()
        assertThat(item.id).isEqualTo("i1")
        assertThat(item.content).isEqualTo("c")
        assertThat(item.done).isFalse()
    }

    @Test
    fun nullTimestampsMapToZero() {
        val doc = TodoDocument().apply {
            id = "bbbbbbbbbbbbbbbbbbbbbbbb"
            title = "t"
        }
        val r = TodoMapper.toResponse(doc)
        assertThat(r.createdAt).isZero()
        assertThat(r.updatedAt).isZero()
        assertThat(r.items).isEmpty()
    }
}
