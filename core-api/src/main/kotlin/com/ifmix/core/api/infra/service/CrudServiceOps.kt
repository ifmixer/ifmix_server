package com.ifmix.core.api.infra.service

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.infra.repo.parseIdOrNull
import com.ifmix.core.api.infra.redis.CacheAside
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

/**
 * 工厂 — 创建绑定了特定实体配置的 CrudServiceOps 实例。
 */
@Component
class CrudServiceOpsFactory(private val cache: CacheAside?) {

    /**
     * 创建带缓存的 ops 实例。
     */
    fun <T : Any, ID : Comparable<ID>> create(
        type: Class<T>,
        idType: KClass<ID>,
        cachePrefix: String,
        idExtractor: (T) -> ID,
    ): CrudServiceOps<T, ID> = CrudServiceOps(cache, cachePrefix, type, idType, idExtractor)

    /**
     * 创建不带缓存的 ops 实例。
     */
    fun <T : Any, ID : Comparable<ID>> createNoCache(
        type: Class<T>,
        idType: KClass<ID>,
        idExtractor: (T) -> ID,
    ): CrudServiceOps<T, ID> = CrudServiceOps(null, "", type, idType, idExtractor)
}

/**
 * 绑定了实体类型 + 缓存配置的通用 CRUD Service 操作。
 * Service 持有一个实例，调用时只传 mc + 业务参数。
 * projectId 固定为 String（slug）；实体主键类型 ID 泛型化。
 */
class CrudServiceOps<T : Any, ID : Comparable<ID>>(
    private val cache: CacheAside?,
    private val cachePrefix: String,
    private val type: Class<T>,
    private val idType: KClass<ID>,
    private val idExtractor: (T) -> ID,
) {

    // ===== Query =====

    fun findById(
        mc: ModuleCtx,
        id: ID,
        loader: (ModuleCtx, String, ID) -> T?,
    ): T? {
        val projectId = mc.mustGetProjectId()
        if (cache == null || !mc.op.readCache) return loader(mc, projectId, id)
        return cache.getOrLoadNullable(cacheKey(projectId, id), type) {
            loader(mc, projectId, id)
        }
    }

    fun findByIds(
        mc: ModuleCtx,
        ids: List<ID>,
        loader: (ModuleCtx, String, Collection<ID>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val projectId = mc.mustGetProjectId()
        if (cache == null || !mc.op.readCache) return loader(mc, projectId, ids)
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(projectId, parseId(it)) },
            type = type,
            idOf = { idExtractor(it).toString() },
        ) { missIds ->
            loader(mc, projectId, missIds.map { parseId(it) })
        }
    }

    fun findByCursor(
        mc: ModuleCtx,
        cursor: String?,
        limit: Int?,
        loader: (ModuleCtx, String, ID?, Int) -> List<T>,
    ): Page<T> {
        val projectId = mc.mustGetProjectId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorId = cursor?.let { parseIdOrNull(idType, it) }
        val items = loader(mc, projectId, cursorId, effectiveLimit + 1)
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
        id: ID,
        deleter: (ModuleCtx, String, ID) -> Boolean,
    ): Boolean {
        val projectId = mc.mustGetProjectId()
        val deleted = deleter(mc, projectId, id)
        if (deleted) evict(projectId, id)
        return deleted
    }

    fun deleteByIds(
        mc: ModuleCtx,
        ids: Collection<ID>,
        deleter: (ModuleCtx, String, Collection<ID>) -> Int,
    ): Int {
        if (ids.isEmpty()) return 0
        val projectId = mc.mustGetProjectId()
        val count = deleter(mc, projectId, ids)
        ids.forEach { evict(projectId, it) }
        return count
    }

    // ===== Evict =====

    fun evict(mc: ModuleCtx, id: ID) = evict(mc.mustGetProjectId(), id)

    private fun evict(projectId: String, id: ID) {
        cache?.evict(cacheKey(projectId, id))
    }

    private fun cacheKey(projectId: String, id: ID) = "$cachePrefix:$projectId:$id"

    private fun parseId(raw: String): ID =
        parseIdOrNull(idType, raw) ?: throw IllegalArgumentException("invalid id: $raw")
}
