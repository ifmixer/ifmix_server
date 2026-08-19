package com.ifmix.api.core.modules.cms.handler

import com.ifmix.api.core.common.http.ModuleCtx
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.modules.cms.entity.FeedbackEntity
import com.ifmix.api.core.modules.cms.repo.FeedbackRepository
import com.ifmix.api.core.modules.feedback.FeedbackCategory
import com.ifmix.api.core.graphql.generated.types.SubmitFeedbackInput
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

/** feedback 写入 handler。负责将 SubmitFeedbackInput 转换为 FeedbackEntity 并持久化。 */
@Component
class FeedbackEntityHandler(
    private val repo: FeedbackRepository,
) {
    fun create(mc: ModuleCtx, input: SubmitFeedbackInput): ObjectId {
        val entity = FeedbackEntity().apply {
            appId = mc.appId.let { ObjectId(it) }
            installId = mc.installId
            userId = mc.userId
            category = input.category?.let { cat ->
                FeedbackCategory.entries.find { it.name == cat.uppercase() }
            }
            note = input.comment
            scanRecordId = input.scanRecordId
        }
        repo.insert(RepoCtx.from(mc), entity)
        return entity.id
    }
}
