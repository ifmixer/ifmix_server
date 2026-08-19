package com.ifmix.api.core.common.db

/**
 * Identifies which MongoDB cluster a request should target.
 * Extensible: add values here and wire routing in MongoClusterResolver implementations.
 */
enum class Cluster {
    DEFAULT,
    // future: AUTH, ANALYTICS, ...
}
