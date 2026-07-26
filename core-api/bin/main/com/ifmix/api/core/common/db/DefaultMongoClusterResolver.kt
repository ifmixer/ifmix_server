package com.ifmix.api.core.common.db

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/** Single-cluster default implementation: ignores appId, always returns the auto-configured unique MongoTemplate. */
@Component
class DefaultMongoClusterResolver(
    private val template: MongoTemplate,
) : MongoClusterResolver {

    override fun forAppId(appId: String): MongoTemplate = template

    override fun primary(): MongoTemplate = template
}
