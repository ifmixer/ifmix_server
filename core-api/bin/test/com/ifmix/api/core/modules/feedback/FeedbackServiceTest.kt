package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.common.http.RequestContext
import org.assertj.core.api.Assertions.assertThat
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class FeedbackServiceTest {

    @Mock
    private lateinit var crud: CRUDService<FeedbackDocument>

    private val ctx = RequestContext(appId = "app-1", installId = "inst-1", userId = "user-1")
    private lateinit var service: FeedbackService

    @BeforeEach
    fun init() {
        service = FeedbackService(crud)
    }

    @Test
    fun submitCreatesFeedbackWithAppId() {
        whenever(crud.createOne(any(), any())).thenReturn("mock-id")

        val id = service.submit(
            ctx,
            SubmitReq(category = FeedbackCategory.LIKED, note = "Great item!"),
        )

        assertThat(id).isEqualTo("mock-id")
    }

    @Test
    fun submitPropagatesInstallIdAndUserIdFromContext() {
        whenever(crud.createOne(any(), any())).thenAnswer {
            val doc = it.getArgument<FeedbackDocument>(1)
            doc.apply {
                assertThat(appId).isEqualTo("app-1")
                assertThat(installId).isEqualTo("inst-1")
                assertThat(userId).isEqualTo("user-1")
            }
            "mock-id"
        }

        service.submit(ctx, SubmitReq(category = FeedbackCategory.FEATURE_REQUEST))
    }

    @Test
    fun submitWithScanRecordIdStoresIt() {
        whenever(crud.createOne(any(), any())).thenAnswer {
            val doc = it.getArgument<FeedbackDocument>(1)
            assertThat(doc.scanRecordId).isEqualTo("scan-123")
            "mock-id"
        }

        service.submit(
            ctx,
            SubmitReq(category = FeedbackCategory.PRICE_TOO_HIGH, scanRecordId = "scan-123"),
        )
    }

    @Test
    fun submitWithoutNoteIsAllowed() {
        whenever(crud.createOne(any(), any())).thenAnswer {
            val doc = it.getArgument<FeedbackDocument>(1)
            assertThat(doc.note).isNull()
            "mock-id"
        }

        val id = service.submit(ctx, SubmitReq(category = FeedbackCategory.MORE_RECOMMENDATIONS))
        assertThat(id).isNotBlank
    }
}
