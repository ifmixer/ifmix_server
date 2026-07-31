package com.ifmix.api.core.repository.ai

import com.ifmix.api.core.entity.ai.AgnesKey
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.unavailableUntil
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AgnesKeyRepository(sql: KSqlClient,) : BaseAppCrudRepository<AgnesKey>(sql, AgnesKey::class) {

    /**
     * 查找所有启用的 key（未软删、供 AiConfig 加载）。
     * @LogicalDeleted 自动过滤 deletedAt IS NOT NULL。
     */
    fun findAllEnabled(): List<AgnesKey> {
        return sql.createQuery(AgnesKey::class) {
            select(table)
        }.execute()
    }

    /**
     * 查找可用 key（未冷却、未软删、appId 匹配）。
     */
    fun findAvailable(appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return sql.createQuery(AgnesKey::class) {
            where(table.appId eq appId)
            where(
                or(
                    table.unavailableUntil.isNull(),
                    table.unavailableUntil lt now
                )
            )
            select(table)
        }.execute()
    }

    /**
     * 标记 key 不可用（设置冷却时间）。
     */
    fun markUnavailable(keyId: UUID, until: Instant) {
        val existing = findById(keyId) ?: return
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
