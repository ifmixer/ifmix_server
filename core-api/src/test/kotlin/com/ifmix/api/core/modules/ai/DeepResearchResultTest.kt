package com.ifmix.core.api.dto.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DeepResearchResultTest {

    private fun result(basicResult: Map<String, Any?>?) = DeepResearchResult(
        scanRecordId = UUID.randomUUID(),
        appId = UUID.randomUUID(),
        basicResult = basicResult,
        premiumResult = null,
        promptVersion = "v10",
    )

    private fun withStatus(status: Any?) =
        result(mapOf("scan_status" to mapOf("status" to status)))

    @Test
    fun `SUCCESS is success`() {
        assertThat(withStatus("SUCCESS").isSuccess).isTrue()
    }

    @Test
    fun `PARTIAL is success`() {
        assertThat(withStatus("PARTIAL").isSuccess).isTrue()
    }

    @Test
    fun `INSUFFICIENT_IMAGE is failure`() {
        val r = withStatus("INSUFFICIENT_IMAGE")
        assertThat(r.isSuccess).isFalse()
        assertThat(r.status).isEqualTo("INSUFFICIENT_IMAGE")
    }

    @Test
    fun `NON_PHYSICAL_SUBJECT is failure`() {
        assertThat(withStatus("NON_PHYSICAL_SUBJECT").isSuccess).isFalse()
    }

    @Test
    fun `lowercase status normalized and treated as success`() {
        val r = withStatus("success")
        assertThat(r.status).isEqualTo("SUCCESS")
        assertThat(r.isSuccess).isTrue()
    }

    @Test
    fun `missing scan_status is failure`() {
        val r = result(mapOf("object_overview" to mapOf("name" to "vase")))
        assertThat(r.isSuccess).isFalse()
        assertThat(r.status).isNull()
        assertThat(r.scanStatus).isNull()
    }

    @Test
    fun `null basicResult is failure`() {
        assertThat(result(null).isSuccess).isFalse()
    }

    @Test
    fun `unknown status is failure`() {
        assertThat(withStatus("WHATEVER").isSuccess).isFalse()
    }

    @Test
    fun `scanStatus exposes detail for client correction hints`() {
        val detail = mapOf(
            "status" to "INSUFFICIENT_IMAGE",
            "recommended_next_photos" to listOf("base/underside", "maker mark"),
        )
        val r = result(mapOf("scan_status" to detail))
        assertThat(r.scanStatus).isEqualTo(detail)
    }
}
