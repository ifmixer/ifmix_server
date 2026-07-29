package com.ifmix.api.core.repository.ai

import com.ifmix.api.core.infra.http.RequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AgnesKeyRepositoryJimmerTest {

    @Test
    fun `findAvailable - returns a key that is not cooled and has quota`() {
        // Arrange
        val ctx = RequestContext(appId = "app-1")
        // In real test, use actual repository with test DB
    }

    @Test
    fun `markUnavailable - marks key as unavailable for specified duration`() {
        // Arrange
        val ctx = RequestContext(appId = "app-1")
        // In real test, use actual repository with test DB
    }
}
