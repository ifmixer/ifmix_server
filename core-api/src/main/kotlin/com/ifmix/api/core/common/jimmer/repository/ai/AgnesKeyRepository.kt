package com.ifmix.api.core.common.jimmer.repository.ai

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.ai.AgnesKey
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class AgnesKeyRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AgnesKey>(sql, AgnesKey::class) {

    /**
     * 查找所有启用的 key（未软删、供 AiConfig 加载）。
     */
    fun findAllEnabled(): List<AgnesKey> {
        // AppScopedFilter 不适用于全局加载场景，此处用 findAll()
        // Jimmer @LogicalDeleted 会自动过滤 deletedAt IS NOT NULL
        return findAll()
    }

    /**
     * 查找可用 key（未冷却、未软删、appId 匹配）。
     */
    fun findAvailable(appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return findAll().filter { key ->
            key.appId == appId &&
            (key.unavailableUntil == null || now.isAfter(key.unavailableUntil!!))
        }
    }

    /**
     * 标记 key 不可用（设置冷却时间）。
     */
    fun markUnavailable(keyId: UUID, until: Instant) {
        val existing = findById(keyId) ?: return
        // Jimmer save with only modified fields
        val updated = AgnesKey {
            id = keyId
            appId = existing.appId
            key = existing.key
            email = existing.email
            type = existing.type
            rateLimit = existing.rateLimit
            windowSec = existing.windowSec
            models = existing.models
            unavailableUntil = until
            createdAt = existing.createdAt
            updatedAt = Instant.now()
        }
        save(updated)
    }
}
