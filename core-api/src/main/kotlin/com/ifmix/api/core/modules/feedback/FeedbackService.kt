package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudService
import com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
import com.ifmix.api.core.common.jimmer.repository.feedback.FeedbackRepository
import org.babyfish.jimmer.Input
import org.springframework.stereotype.Service

/**
 * Feedback 业务逻辑。继承自 BaseAppCrudService，获得基本 CRUD 操作。
 * 领域特有方法（如 submit）在此追加。
 */
@Service
class FeedbackService(
    feedbackRepo: FeedbackRepository,
) : BaseAppCrudService<Feedback>(feedbackRepo) {

    /**
     * 提交反馈，返回新建实体。直接委托 base create(Input) 方法。
     */
    fun submit(input: Input<Feedback>): Feedback =
        create(input)
}
