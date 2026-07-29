package com.ifmix.api.core.service.antique

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class AntiqueServiceTest {

    @Mock
    private lateinit var scanRunner: ScanRunner

    @Mock
    private lateinit var objectStorage: ObjectStorage

    @Mock
    private lateinit var rateLimiter: RateLimiter

    @Mock
    private lateinit var mongo: MongoTemplate

    private lateinit var service: AntiqueService
    private val ctx = RequestContext(appId = "test-app-1")

    @BeforeEach
    fun setUp() {
        service = AntiqueService(scanRunner, objectStorage, rateLimiter, mongo)
    }

    @Test
    fun `createScan throws RATE_LIMITED when over quota`() {
        val request = CreateScanRequest(imageUrl = "https://example.com/photo.png")

        val limitedResult = RateLimiter.CheckResult.limited(101L)
        whenever(rateLimiter.check(eq(ctx), eq("test-app-1"))).thenReturn(limitedResult)

        val error = org.junit.jupiter.api.assertThrows<ApiError> {
            service.createScan(ctx, request)
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)

        Mockito.verifyNoInteractions(objectStorage, mongo)
    }

    @Test
    fun `getScanResult returns DTO for existing record`() {
        val doc = ScanRecordDocument().apply {
            id = "existing-id"
            scanId = "scan-001"
            imageUrl = "https://example.com/photo.png"
            status = "COMPLETED"
            resultJson = "{\"tags\":[]}"
            tier = "FREE"
            clientIp = "1.2.3.4"
            relatedId = "todo-123"
            appId = "test-app-1"
        }

        whenever(mongo.findById(eq("existing-id"), eq(ScanRecordDocument::class.java)))
            .thenReturn(doc)

        val dto = service.getScanResult(ctx, "existing-id")

        assertThat(dto.id).isEqualTo("existing-id")
        assertThat(dto.status).isEqualTo("COMPLETED")
        assertThat(dto.tier).isEqualTo("FREE")
    }

    @Test
    fun `getScanResult throws NOT_FOUND for missing record`() {
        whenever(mongo.findById(eq("missing-id"), eq(ScanRecordDocument::class.java)))
            .thenReturn(null)

        val error = org.junit.jupiter.api.assertThrows<ApiError> {
            service.getScanResult(ctx, "missing-id")
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    @Test
    fun `presignedDownloadUrl delegates to objectStorage`() {
        whenever(objectStorage.presignDownload(eq("antique/test.png"), eq(Duration.ofHours(1))))
            .thenReturn("https://download.presigned.url")

        val url = service.presignedDownloadUrl("antique/test.png", Duration.ofHours(1))

        assertThat(url).isEqualTo("https://download.presigned.url")
    }
}
