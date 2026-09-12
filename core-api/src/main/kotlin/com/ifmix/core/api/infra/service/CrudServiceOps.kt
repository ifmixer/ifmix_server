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
        val projectId = mc.mustGetProjectId()
        if (cache == null || !mc.op.readCache) return loader(mc, projectId, id)
        return cache.getOrLoadNullable(cacheKey(projectId, id), type) {
            loader(mc, projectId, id)
        }
    }

    fun findByIds(
        mc: ModuleCtx,
        ids: List<UUID>,
        loader: (ModuleCtx, UUID, Collection<UUID>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val projectId = mc.mustGetProjectId()
        if (cache == null || !mc.op.readCache) return loader(mc, projectId, ids)
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(projectId, UUID.fromString(it)) },
            type = type,
            idOf = { idExtractor(it).toString() },
        ) { missIds ->
            loader(mc, projectId, missIds.map { UUID.fromString(it) })
        }
    }

    fun findByCursor(
        mc: ModuleCtx,
        cursor: String?,
        limit: Int?,
        loader: (ModuleCtx, UUID, UUID?, Int) -> List<T>,
    ): Page<T> {
        val projectId = mc.mustGetProjectId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = loader(mc, projectId, cursorUuid, effectiveLimit + 1)
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
        val projectId = mc.mustGetProjectId()
        val deleted = deleter(mc, projectId, id)
        if (deleted) evict(projectId, id)
        return deleted
    }

    fun deleteByIds(
        mc: ModuleCtx,
        ids: Collection<UUID>,
        deleter: (ModuleCtx, UUID, Collection<UUID>) -> Int,
    ): Int {
        if (ids.isEmpty()) return 0
        val projectId = mc.mustGetProjectId()
        val count = deleter(mc, projectId, ids)
        ids.forEach { evict(projectId, it) }
        return count
    }

    // ===== Evict =====

    fun evict(mc: ModuleCtx, id: UUID) = evict(mc.mustGetProjectId(), id)

    private fun evict(projectId: UUID, id: UUID) {
        cache?.evict(cacheKey(projectId, id))
    }

    private fun cacheKey(projectId: UUID, id: UUID) = "$cachePrefix:$projectId:$id"
}
