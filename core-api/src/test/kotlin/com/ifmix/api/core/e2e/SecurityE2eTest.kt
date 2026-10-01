package com.ifmix.core.api.e2e

import com.ifmix.core.api.e2e.support.E2eTestBase
import com.ifmix.core.api.e2e.support.TestFixtures
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.codec.toBase58
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import java.util.UUID

/**
 * 安全边界 E2E 测试。
 */
@DisplayName("Security E2E")
class SecurityE2eTest : E2eTestBase() {

    @Autowired
    lateinit var fixtures: TestFixtures

    @Autowired
    lateinit var authJwt: AuthJwtService

    /** 签发 customer token 用的测试主体（presignDownload 不做 DB 反查，无需真实存在）。 */
    private val testCustomerId = UUID.fromString("00000000-0000-0000-0000-0000000000c5")
    private val testCustomerB58 = testCustomerId.toBase58()

    @BeforeEach
    fun setup() {
        fixtures.seedMinimal()
    }

    /** customer token：presign 类接口要求已认证的 customer actor。 */
    private fun customerAuthHeader(): String {
        val token = authJwt.signAccess(
            actorId = testCustomerId.toString(),
            actorType = AuthJwtService.ACTOR_CUSTOMER,
            projectId = TEST_PROJECT_ID,
            sessionId = "00000000-0000-0000-0000-0000000000aa",
            anonymous = false,
            installId = TEST_INSTALL_ID,
        )
        return "Bearer $token"
    }

    private fun postAuthed(path: String, body: Any?) =
        post(path, body).header("Authorization", customerAuthHeader())

    @Nested
    @DisplayName("Header Validation")
    inner class HeaderValidation {

        @Test
        fun `missing x-project-id returns 400`() {
            webClient.put()
                .uri("/customer/query/core/auth/me")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `invalid x-project-id format returns 400`() {
            webClient.put()
                .uri("/customer/query/core/auth/me")
                .header("x-project-id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `valid x-project-id without token returns 401`() {
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
            postAuthed(
                "/customer/mutation/core/storage/presignDownload",
                mapOf("imageKey" to "image/p/${TEST_PROJECT_ID}/antique_scan/customer/${testCustomerB58}/../etc/passwd"),
            )
                .exchange()
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.msg").value<String> { assert(it.contains("invalid objectKey")) }
        }

        @Test
        fun `invalid objectKey format returns 400`() {
            postAuthed(
                "/customer/mutation/core/storage/presignDownload",
                mapOf("imageKey" to "random/path/file.png"),
            )
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `objectKey with mismatched projectId returns 400`() {
            val otherAppId = "99999999-9999-9999-9999-999999999999"
            postAuthed(
                "/customer/mutation/core/storage/presignDownload",
                mapOf("imageKey" to "image/p/$otherAppId/antique_scan/customer/${testCustomerB58}/file.png"),
            )
                .exchange()
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.msg").value<String> { assert(it.contains("invalid objectKey")) }
        }

        @Test
        fun `objectKey with mismatched owner actor returns 400`() {
            postAuthed(
                "/customer/mutation/core/storage/presignDownload",
                mapOf("imageKey" to "image/p/${TEST_PROJECT_ID}/antique_scan/customer/otherowner/file.png"),
            )
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `valid objectKey with install prefix succeeds`() {
            postAuthed(
                "/customer/mutation/core/storage/presignUpload",
                mapOf("contentType" to "IMAGE_JPEG", "prefix" to "antique_scan"),
            )
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.data.uploadUrl").isNotEmpty
                .jsonPath("$.data.imageKey").isNotEmpty
        }
    }
}
