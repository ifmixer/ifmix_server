package com.ifmix.core.api.infra.service

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.infra.http.OperationContext

import com.ifmix.core.api.infra.redis.CacheAside
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 工厂 — 创建绑定了特定实体配置的 CrudServiceOps 实例。
 */
@Component
class CrudServiceOpsFactory(private val cache: CacheAside?) {

    /**
     * 创建带缓存的 ops 实例。
     */
    fun <T : Any> create(
        type: Class<T>,
        cachePrefix: String,
        idExtractor: (T) -> UUID,
    ): CrudServiceOps<T> = CrudServiceOps(cache, cachePrefix, type, idExtractor)

    /**
     * 创建不带缓存的 ops 实例。
     */
    fun <T : Any> createNoCache(
        type: Class<T>,
        idExtractor: (T) -> UUID,
    ): CrudServiceOps<T> = CrudServiceOps(null, "", type, idExtractor)
}

/**
 * 绑定了实体类型 + 缓存配置的通用 CRUD Service 操作。
 * Service 持有一个实例，调用时只传 mc + 业务参数。
 */
class CrudServiceOps<T : Any>(
    private val cache: CacheAside?,
    private val cachePrefix: String,
    private val type: Class<T>,
    private val idExtractor: (T) -> UUID,
) {

    // ===== Query =====

    fun findById(
        mc: ModuleCtx,
        id: UUID,
        loader: (ModuleCtx, UUID, UUID) -> T?,
    ): T? {
        val appId = mc.mustGetAppId()
        if (cache == null || !mc.op.readCache) return loader(mc, appId, id)
        return cache.getOrLoadNullable(cacheKey(appId, id), type) {
            loader(mc, appId, id)
        }
    }

    fun findByIds(
        mc: ModuleCtx,
        ids: List<UUID>,
        loader: (ModuleCtx, UUID, Collection<UUID>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val appId = mc.mustGetAppId()
        if (cache == null || !mc.op.readCache) return loader(mc, appId, ids)
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(appId, UUID.fromString(it)) },
            type = type,
            idOf = { idExtractor(it).toString() },
        ) { missIds ->
            loader(mc, appId, missIds.map { UUID.fromString(it) })
        }
    }

    fun findByCursor(
        mc: ModuleCtx,
        cursor: String?,
        limit: Int?,
        loader: (ModuleCtx, UUID, UUID?, Int) -> List<T>,
    ): Page<T> {
        val appId = mc.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = loader(mc, appId, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            pageInfo = PageInfo(
                nextCursor = resultItems.lastOrNull()?.let { idExtractor(it).toString() },
                hasMore = hasMore,
            ),
        )
    }

    // ===== Delete + Evict =====

    fun deleteById(
        mc: ModuleCtx,
        id: UUID,
        deleter: (ModuleCtx, UUID, UUID) -> Boolean,
    ): Boolean {
        val appId = mc.mustGetAppId()
        val deleted = deleter(mc, appId, id)
        if (deleted) evict(appId, id)
        return deleted
    }

    fun deleteByIds(
        mc: ModuleCtx,
        ids: Collection<UUID>,
        deleter: (ModuleCtx, UUID, Collection<UUID>) -> Int,
    ): Int {
        if (ids.isEmpty()) return 0
        val appId = mc.mustGetAppId()
        val count = deleter(mc, appId, ids)
        ids.forEach { evict(appId, it) }
        return count
    }

    // ===== Evict =====

    fun evict(mc: ModuleCtx, id: UUID) = evict(mc.mustGetAppId(), id)

    private fun evict(appId: UUID, id: UUID) {
        cache?.evict(cacheKey(appId, id))
    }

    private fun cacheKey(appId: UUID, id: UUID) = "$cachePrefix:$appId:$id"
}
