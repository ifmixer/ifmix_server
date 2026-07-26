package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * feedback 模块集成测试（Testcontainers）。
 * 本地开发可跳过运行（需 MongoDB Testcontainer 可用）。
 */
class FeedbackIntegrationTest : AbstractMongoTest() {

    private lateinit var service: FeedbackService

    private val ctx = RequestContext(appId = "app-int", installId = "inst-int", userId = "user-int")

    @BeforeEach
    fun setUp() {
        mongoTemplate.getCollection("feedback").deleteMany(Document())
        service = FeedbackService(
            com.ifmix.api.core.common.service.CRUDService(
                com.ifmix.api.core.common.db.CRUDRepository(mongoTemplate, FeedbackDocument::class.java),
            ),
        )
    }

    @Test
    fun submitFeedbackIsPersistedAndRetrievable() {
        val id = service.submit(
            ctx,
            SubmitReq(category = FeedbackCategory.PRICE_MISSING, note = "No price shown"),
        )

        assertThat(id).isNotBlank
        val doc = service.crud.getById(ctx, id)
        assertThat(doc.appId).isEqualTo("app-int")
        assertThat(doc.category).isEqualTo(FeedbackCategory.PRICE_MISSING)
        assertThat(doc.note).isEqualTo("No price shown")
        assertThat(doc.installId).isEqualTo("inst-int")
        assertThat(doc.userId).isEqualTo("user-int")
        assertThat(doc.createdAt).isNotNull
        assertThat(doc.updatedAt).isNotNull
    }

    @Test
    fun submitFeedbackWithoutOptionalFields() {
        val id = service.submit(
            ctx,
            SubmitReq(category = FeedbackCategory.LIKED),
        )

        assertThat(id).isNotBlank
        val doc = service.crud.getById(ctx, id)
        assertThat(doc.note).isNull()
        assertThat(doc.scanRecordId).isNull()
    }
}
