package com.ifmix.api.core.modules.antique

import io.mcarle.konvert.api.Konverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ScanMapperTest {

    private val mapper: ScanMapper = Konverter.get()

    @Test
    fun mapsDocumentToDto() {
        val doc = ScanRecordDocument().apply {
            id = "aaaaaaaaaaaaaaaaaaaaaaaa"
            scanId = "scan-001"
            imageUrl = "https://example.com/photo.png"
            status = "COMPLETED"
            resultJson = "{\"tags\":[\"ceramic\",\"ancient\"]}"
            tier = "FREE"
            clientIp = "203.0.113.50"
            relatedId = "todo-123"
            createdAt = Instant.ofEpochMilli(1000)
            updatedAt = Instant.ofEpochMilli(2000)
        }

        val dto = mapper.toDto(doc)

        assertThat(dto.id).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(dto.scanId).isEqualTo("scan-001")
        assertThat(dto.imageUrl).isEqualTo("https://example.com/photo.png")
        assertThat(dto.status).isEqualTo("COMPLETED")
        assertThat(dto.resultJson).isEqualTo("{\"tags\":[\"ceramic\",\"ancient\"]}")
        assertThat(dto.tier).isEqualTo("FREE")
        assertThat(dto.clientIp).isEqualTo("203.0.113.50")
        assertThat(dto.relatedId).isEqualTo("todo-123")
        assertThat(dto.createdAt).isEqualTo(Instant.ofEpochMilli(1000))
        assertThat(dto.updatedAt).isEqualTo(Instant.ofEpochMilli(2000))
    }

    @Test
    fun mapsDocumentToListItemDto() {
        val doc = ScanRecordDocument().apply {
            id = "bbbbbbbbbbbbbbbbbbbbbbbb"
            scanId = "scan-002"
            imageUrl = "https://example.com/photo2.png"
            status = "PENDING"
            createdAt = Instant.ofEpochMilli(5000)
        }

        val item = mapper.toListItemDto(doc)

        assertThat(item.id).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbb")
        assertThat(item.scanId).isEqualTo("scan-002")
        assertThat(item.status).isEqualTo("PENDING")
        assertThat(item.imageUrl).isEqualTo("https://example.com/photo2.png")
        assertThat(item.createdAt).isEqualTo(Instant.ofEpochMilli(5000))
    }

    @Test
    fun nullTimestampsStayNull() {
        val doc = ScanRecordDocument().apply {
            id = "cccccccccccccccccccccccc"
            status = "FAILED"
        }

        val dto = mapper.toDto(doc)

        assertThat(dto.id).isEqualTo("cccccccccccccccccccccccc")
        assertThat(dto.status).isEqualTo("FAILED")
        assertThat(dto.createdAt).isNull()
        assertThat(dto.updatedAt).isNull()
    }
}
