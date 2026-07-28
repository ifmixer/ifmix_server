package com.ifmix.api.core.common.jimmer.repository.feedback

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
import org.springframework.stereotype.Component

@Component
class FeedbackRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Feedback>(clusterRegistry, Feedback::class)
