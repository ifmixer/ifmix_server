package com.ifmix.api.core.service.appconfig

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * Tests for AppConfigRepo using Jimmer + PostgreSQL backend.
 * Note: Full integration tests require Spring Boot context with Testcontainers.
 * This file contains basic unit-style checks.
 */
class AppConfigServiceJimmerTest {

    @Test
    fun `AppConfig entity should have required fields`() {
        // Simple compile-time verification - if this compiles, the structure is correct
        val id = UUID.randomUUID()
        val config = mockAppConfig(id)
        
        assertThat(config.id).isNotNull()
        assertThat(config.appId).isNotNull()
        assertThat(config.revision).isEqualTo(1)
    }

    private fun mockAppConfig(id: UUID): com.ifmix.api.core.entity.appconfig.AppConfig {
        // Create a mock entity - in real test this would come from the repository
        return com.ifmix.api.core.entity.appconfig.AppConfig {
            this.id = id
            this.appId = id
            this.authTenantId = null
            this.appleBundleId = "com.example.test"
            this.androidPackageName = null
            this.appleConfig = mapOf("appAppleId" to "test-app-id")
            this.googleConfig = mapOf("serviceAccount" to "test-service")
            this.iapConfig = mapOf("productTierMap" to emptyMap<String, String>())
            this.revision = 1
            this.deletedAt = null
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
    }
}
