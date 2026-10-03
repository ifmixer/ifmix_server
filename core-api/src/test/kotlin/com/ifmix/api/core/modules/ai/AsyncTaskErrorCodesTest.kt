package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AsyncTaskErrorCodesTest {
    @Test
    fun `shared async error codes keep their external values`() {
        assertThat(AiTaskErrorCodes.AI_FAILED).isEqualTo("AI_FAILED")
        assertThat(AiTaskErrorCodes.TIMEOUT).isEqualTo("TIMEOUT")
        assertThat(AiTaskErrorCodes.TASK_SUBMISSION_FAILED).isEqualTo("TASK_SUBMISSION_FAILED")
        assertThat(AiTaskErrorCodes.INTERNAL_ERROR).isEqualTo("INTERNAL_ERROR")
    }

    @Test
    fun `scan task context permits missing install id`() {
        val context = ScanTaskContext(
            projectId = "test-app",
            customerId = UUID.randomUUID(),
            installId = null,
            scanId = UUID.randomUUID(),
            locale = null,
            country = null,
            currency = null,
            images = emptyList(),
            collected = false,
            promptVersion = "v10",
            createdAt = Instant.now(),
        )

        assertThat(context.installId).isNull()
    }
}
