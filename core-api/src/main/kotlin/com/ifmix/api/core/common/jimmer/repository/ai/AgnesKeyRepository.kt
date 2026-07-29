package com.ifmix.api.core.common.jimmer.repository.ai

import com.ifmix.api.core.common.auth.RequestContext
import com.ifmix.api.core.common.jimmer.entity.agnes_key.AgnesKey
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class AgnesKeyRepository(
    private val sql: KSqlClient,
) {

    /**
     * 查找一个可用的 Agnes Key（未软删、未冷却、类型为 PRIMARY）。
     */
    fun findAvailable(ctx: RequestContext): AgnesKey? {
        val now = Instant.now()
        val appIdUUID = UUID.fromString(ctx.appId)

        // Simple approach: fetch all and filter (more compatible with KSP)
        val all = this.findAll()
        return all.firstOrNull { key ->
            key.appId == appIdUUID &&
            key.deletedAt == null &&
            key.type == "PRIMARY" &&
            (key.unavailableUntil == null || !key.unavailableUntil!!.isAfter(now))
        }
    }

    /**
     * 标记键为不可用（设置 unavailableUntil）。
     */
    fun markUnavailable(
        ctx: RequestContext,
        keyId: String,
        duration: Long = 60_000, // default 1 minute
    ): Boolean {
        val uuidId = UUID.fromString(keyId)
        val now = Instant.now()
        val unavailableUntil = now.plusMillis(duration)
        val appIdUUID = UUID.fromString(ctx.appId)

        // Update via input
        val existing = findById(ctx, keyId) ?: return false

        val input: Input<AgnesKey> = sql.input(AgnesKey::class.java) {
            set("id", uuidId)
            set("unavailableUntil", unavailableUntil)
            set("updatedAt", now)
            // Copy unchanged fields
            set("appId", existing.appId)
            set("key", existing.key)
            set("email", existing.email)
            set("type", existing.type)
            set("rateLimit", existing.rateLimit)
            set("windowSec", existing.windowSec)
            set("models", existing.models)
            set("deletedAt", existing.deletedAt)
            set("createdAt", existing.createdAt)
        }
        save(input) != true
    }

    /**
     * 获取所有可用的 Keys。
     */
    fun listAvailable(ctx: RequestContext): List<AgnesKey> {
        val now = Instant.now()
        val appIdUUID = UUID.fromString(ctx.appId)

        return this.findAll().filter { key ->
            key.appId == appIdUUID &&
            key.deletedAt == null &&
            (key.unavailableUntil == null || !key.unavailableUntil!!.isAfter(now))
        }.sortedByDescending { it.createdAt }
    }

    /**
     * 创建一个新的 Agnes Key。
     */
    fun create(
        ctx: RequestContext,
        key: String?,
        email: String?,
        type: String? = "PRIMARY",
        rateLimit: Long = -1,
        windowSec: Long = 86_400L,
        models: String? = null,
    ): AgnesKey {
        val now = Instant.now()
        val appIdUUID = UUID.fromString(ctx.appId)

        val input: Input<AgnesKey> = sql.input(AgnesKey::class.java) {
            set("id", UUID.randomUUID())
            set("appId", appIdUUID)
            set("key", key)
            set("email", email)
            set("type", type)
            set("rateLimit", rateLimit)
            set("windowSec", windowSec)
            set("models", models)
            set("unavailableUntil", null)
            set("deletedAt", null)
            set("createdAt", now)
            set("updatedAt", now)
        }
        return save(input)!!
    }

    /**
     * 通过 ID 查找 Agnes Key（带软删除检查）。
     */
    fun findById(ctx: RequestContext, id: String): AgnesKey? {
        val uuidId = UUID.fromString(id)
        val appIdUUID = UUID.fromString(ctx.appId)

        return this.findAll().firstOrNull { key ->
            key.id == uuidId &&
            key.appId == appIdUUID &&
            key.deletedAt == null
        }
    }

    /**
     * 通过密钥查找 Agnes Key（带软删除检查）。
     */
    fun findByKey(ctx: RequestContext, key: String): AgnesKey? {
        val appIdUUID = UUID.fromString(ctx.appId)

        return this.findAll().firstOrNull { k ->
            k.key == key &&
            k.appId == appIdUUID &&
            k.deletedAt == null
        }
    }

    // Helper to get all keys (from underlying query)
    private fun findAll(): List<AgnesKey> {
        // This would typically be implemented using Jimmer's query API
        // For simplicity, we'll leave it as stub - actual implementation needs proper Jimmer query
        return emptyList()
    }

    // Save operation
    private fun save(input: Input<AgnesKey>?): AgnesKey? {
        // Implementation needed
        return null
    }
}
