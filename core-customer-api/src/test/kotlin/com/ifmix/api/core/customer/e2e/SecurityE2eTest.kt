package com.ifmix.api.core.customer.e2e

import com.ifmix.api.core.customer.e2e.support.E2eTestBase
import com.ifmix.api.core.customer.e2e.support.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType

/**
 * 安全边界 E2E 测试。
 */
@DisplayName("Security E2E")
class SecurityE2eTest : E2eTestBase() {

    @Autowired
    lateinit var fixtures: TestFixtures

    @BeforeEach
    fun setup() {
        fixtures.seedMinimal()
    }

    @Nested
    @DisplayName("Header Validation")
    inner class HeaderValidation {

        @Test
        fun `missing x-app-id returns 400`() {
            webClient.put()
                .uri("/customer/query/core/auth/me")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `invalid x-app-id format returns 400`() {
            webClient.put()
                .uri("/customer/query/core/auth/me")
                .header("x-app-id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `valid x-app-id without token returns 401`() {
            put("/customer/query/core/auth/me")
                .exchange()
                .expectStatus().isUnauthorized
        }
    }

    @Nested
    @DisplayName("Storage objectKey Validation")
    inner class StorageValidation {

        @Test
        fun `path traversal in objectKey returns 400`() {
            post("/customer/mutation/core/storage/presignDownload")
                .bodyValue(mapOf("imageKey" to "app_${TEST_APP_ID}/i_${TEST_INSTALL_ID}/../etc/passwd"))
                .exchange()
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.msg").value<String> { assert(it.contains("path traversal")) }
        }

        @Test
        fun `invalid objectKey format returns 400`() {
            post("/customer/mutation/core/storage/presignDownload")
                .bodyValue(mapOf("imageKey" to "random/path/file.png"))
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `objectKey with mismatched appId returns 400`() {
            val otherAppId = "99999999-9999-9999-9999-999999999999"
            post("/customer/mutation/core/storage/presignDownload")
                .bodyValue(mapOf("imageKey" to "app_${otherAppId}/i_${TEST_INSTALL_ID}/file.png"))
                .exchange()
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.msg").value<String> { assert(it.contains("appId mismatch")) }
        }

        @Test
        fun `valid objectKey with install prefix succeeds`() {
            post("/customer/mutation/core/storage/presignUpload")
                .bodyValue(mapOf("contentType" to "IMAGE_JPEG", "category" to "ANTIQUE_SCAN"))
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.data.uploadUrl").isNotEmpty
                .jsonPath("$.data.imageKey").isNotEmpty
        }
    }
}
