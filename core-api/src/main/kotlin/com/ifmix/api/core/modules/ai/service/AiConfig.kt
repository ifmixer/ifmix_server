package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.modules.ai.repo.AgnesKeyRepository
import org.springframework.beans.factory.annotation.Value
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * AI 模块 bean 装配 — 直注册为 @Component。
 *
 * AgnesKeyStore / AgnesChatClientFactory 始终加载，
 * SpringAiScanRunner 已经是 @Service @Primary。
 */
@Component
class AiConfig(
    private val sqlClient: KSqlClient,
    private val redis: StringRedisTemplate,
    private val agnesKeyRepo: AgnesKeyRepository,
)
