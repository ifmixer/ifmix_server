package com.ifmix.api.core.common.redis

import org.springframework.data.redis.core.RedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 显式 Cache-Aside 工具。封装 Redis 读/写/失效，调用方自己决定哪里走缓存。
 *
 * 用法：
 * ```
 * // 单个
 * cache.getOrLoad(key, MyDoc::class.java) { repo.getById(id) }
 *
 * // 批量（自动拆 hit/miss，只查 miss 的）
 * cache.loadMany(ids, { "todo:$appId:$it" }, MyDoc::class.java, { it.id }) { missIds ->
 *     repo.findByIds(missIds)
 * }
 * ```
 */
class CacheAside(
    private val redis: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val defaultTtl: Duration = Duration.ofMinutes(5),
) {

    /** 仅查缓存，不触发加载。miss 返回 null。 */
    fun <T> get(key: String, type: Class<T>): T? {
        val cached = redis.opsForValue().get(key) ?: return null
        return objectMapper.readValue(cached, type)
    }

    /** 先查 Redis，miss 则调 loader 加载并写入缓存。 */
    fun <T> getOrLoad(key: String, type: Class<T>, ttl: Duration = defaultTtl, loader: () -> T): T {
        val cached = redis.opsForValue().get(key)
        if (cached != null) {
            return objectMapper.readValue(cached, type)
        }
        val value = loader()
        redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl)
        return value
    }

    /** 同 getOrLoad，但 loader 可返回 null（null 不缓存）。 */
    fun <T> getOrLoadNullable(key: String, type: Class<T>, ttl: Duration = defaultTtl, loader: () -> T?): T? {
        val cached = redis.opsForValue().get(key)
        if (cached != null) {
            return objectMapper.readValue(cached, type)
        }
        val value = loader() ?: return null
        redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl)
        return value
    }

    /**
     * 批量 cache-aside：对每个 id 先查缓存，miss 的批量调 loader，结果回填缓存。
     *
     * @param ids 要查的 id 列表
     * @param keyOf id → cache key 的映射函数
     * @param type 反序列化目标类型
     * @param idOf 从加载结果提取 id（用于回填缓存时构建 key）
     * @param loader 批量加载函数，只收到 miss 的 id 列表
     */
    fun <T> loadMany(
        ids: List<String>,
        keyOf: (String) -> String,
        type: Class<T>,
        idOf: (T) -> String,
        ttl: Duration = defaultTtl,
        loader: (List<String>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val hits = mutableListOf<T>()
        val missIds = mutableListOf<String>()
        for (id in ids) {
            val cached = redis.opsForValue().get(keyOf(id))
            if (cached != null) {
                hits.add(objectMapper.readValue(cached, type))
            } else {
                missIds.add(id)
            }
        }
        if (missIds.isNotEmpty()) {
            val loaded = loader(missIds)
            loaded.forEach { item ->
                redis.opsForValue().set(keyOf(idOf(item)), objectMapper.writeValueAsString(item), ttl)
            }
            hits.addAll(loaded)
        }
        return hits
    }

    /** 主动写入缓存（预热）。 */
    fun put(key: String, value: Any, ttl: Duration = defaultTtl) {
        redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl)
    }

    /** 主动失效。 */
    fun evict(key: String) {
        redis.delete(key)
    }

    /** 批量失效。 */
    fun evictAll(keys: Collection<String>) {
        if (keys.isNotEmpty()) redis.delete(keys)
    }
}
