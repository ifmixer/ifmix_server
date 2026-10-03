package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.ai.ScanTaskContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PushFeatureFlagContextTest {
    @Test
    fun `scan push flag defaults off and preserves explicit value`() {
        val base = ScanTaskContext(
            projectId = "test-app",
            customerId = UUID.randomUUID(),
            installId = UUID.randomUUID(),
            scanId = UUID.randomUUID(),
            locale = null,
            country = null,
            currency = null,
            images = emptyList(),
            collected = false,
            promptVersion = "v10",
            createdAt = Instant.now(),
        )

        assertThat(base.scanResultPushEnabled).isFalse()
        assertThat(base.copy(scanResultPushEnabled = true).scanResultPushEnabled).isTrue()
    }

    @Test
    fun `deep research push flag defaults off and preserves explicit value`() {
        val base = DeepResearchTaskContext(
            projectId = "test-app",
            customerId = UUID.randomUUID(),
            deepResearchId = UUID.randomUUID(),
            scanRecordId = UUID.randomUUID(),
            images = emptyList(),
            locale = null,
            country = null,
            currency = null,
            promptVersion = "v10",
            createdAt = Instant.now(),
        )

        assertThat(base.deepResearchPushEnabled).isFalse()
        assertThat(base.copy(deepResearchPushEnabled = true).deepResearchPushEnabled).isTrue()
    }
}
