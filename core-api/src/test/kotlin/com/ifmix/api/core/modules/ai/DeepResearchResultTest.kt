package com.ifmix.core.api.dto.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import java.util.UUID

class DeepResearchResultTest {
    private fun result(basicResult: Map<String, Any?>?) = DeepResearchResult(
        scanRecordId = UUID.randomUUID(),
        projectId = "test-app",
        basicResult = basicResult,
        premiumResult = null,
        promptVersion = "v10",
    )

    private fun withStatus(status: Any?) =
        result(mapOf("scan_status" to mapOf("status" to status)))

    @Test
    fun `business status remains available without deciding task outcome`() {
        assertThat(withStatus("SUCCESS").status).isEqualTo("SUCCESS")
        assertThat(withStatus("PARTIAL").status).isEqualTo("PARTIAL")
        assertThat(withStatus("INSUFFICIENT_IMAGE").status).isEqualTo("INSUFFICIENT_IMAGE")
        assertThat(withStatus("NON_PHYSICAL_SUBJECT").status).isEqualTo("NON_PHYSICAL_SUBJECT")
    }

    @Test
    fun `status is normalized`() {
        assertThat(withStatus("success").status).isEqualTo("SUCCESS")
    }

    @Test
    fun `missing scan status remains null`() {
        val r = result(mapOf("object_overview" to mapOf("name" to "vase")))
        assertThat(r.status).isNull()
        assertThat(r.scanStatus).isNull()
    }

    @Test
    fun `scan status exposes business detail`() {
        val detail = mapOf(
            "status" to "INSUFFICIENT_IMAGE",
            "recommended_next_photos" to listOf("base/underside", "maker mark"),
        )
        assertThat(result(mapOf("scan_status" to detail)).scanStatus).isEqualTo(detail)
    }
}
