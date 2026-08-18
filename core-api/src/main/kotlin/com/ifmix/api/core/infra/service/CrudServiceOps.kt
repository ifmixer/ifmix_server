package com.ifmix.api.core.infra.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.OperationContext

import com.ifmix.api.core.infra.redis.CacheAside
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
 * Service 持有一个实例，调用时只传 svcCtx + 业务参数。
 */
class CrudServiceOps<T : Any>(
    private val cache: CacheAside?,
    private val cachePrefix: String,
    private val type: Class<T>,
    private val idExtractor: (T) -> UUID,
) {

    // ===== Query =====

    fun findById(
        svcCtx: SvcCtx,
        id: UUID,
        loader: (SvcCtx, UUID, UUID) -> T?,
    ): T? {
        val appId = svcCtx.mustGetAppId()
        if (cache == null || !svcCtx.op.readCache) return loader(svcCtx, appId, id)
        return cache.getOrLoadNullable(cacheKey(appId, id), type) {
            loader(svcCtx, appId, id)
        }
    }

    fun findByIds(
        svcCtx: SvcCtx,
        ids: List<UUID>,
        loader: (SvcCtx, UUID, Collection<UUID>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val appId = svcCtx.mustGetAppId()
        if (cache == null || !svcCtx.op.readCache) return loader(svcCtx, appId, ids)
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(appId, UUID.fromString(it)) },
            type = type,
            idOf = { idExtractor(it).toString() },
        ) { missIds ->
            loader(svcCtx, appId, missIds.map { UUID.fromString(it) })
        }
    }

    fun findByCursor(
        svcCtx: SvcCtx,
        cursor: String?,
        limit: Int?,
        loader: (SvcCtx, UUID, UUID?, Int) -> List<T>,
    ): Page<T> {
        val appId = svcCtx.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = loader(svcCtx, appId, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.let { idExtractor(it).toString() },
            hasMore = hasMore,
        )
    }

    // ===== Delete + Evict =====

    fun deleteById(
        svcCtx: SvcCtx,
        id: UUID,
        deleter: (SvcCtx, UUID, UUID) -> Boolean,
    ): Boolean {
        val appId = svcCtx.mustGetAppId()
        val deleted = deleter(svcCtx, appId, id)
        if (deleted) evict(appId, id)
        return deleted
    }

    fun deleteByIds(
        svcCtx: SvcCtx,
        ids: Collection<UUID>,
        deleter: (SvcCtx, UUID, Collection<UUID>) -> Int,
    ): Int {
        if (ids.isEmpty()) return 0
        val appId = svcCtx.mustGetAppId()
        val count = deleter(svcCtx, appId, ids)
        ids.forEach { evict(appId, it) }
        return count
    }

    // ===== Evict =====

    fun evict(svcCtx: SvcCtx, id: UUID) = evict(svcCtx.mustGetAppId(), id)

    private fun evict(appId: UUID, id: UUID) {
        cache?.evict(cacheKey(appId, id))
    }

    private fun cacheKey(appId: UUID, id: UUID) = "$cachePrefix:$appId:$id"
}
