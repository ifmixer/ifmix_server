package com.ifmix.api.core.common.db

import org.springframework.data.mongodb.core.MongoTemplate

/**
 * Cluster routing seam: future routing to different MongoDB clusters by appId.
 * The default implementation ignores appId. When multi-cluster support is needed,
 * replace this implementation and business code requires zero changes.
 */
interface MongoClusterResolver {
    /** Returns the template for the cluster corresponding to the given appId. */
    fun forAppId(appId: String): MongoTemplate

    /** Default/primary cluster template (used for bean injection in no-appId scenarios). */
    fun primary(): MongoTemplate
}
