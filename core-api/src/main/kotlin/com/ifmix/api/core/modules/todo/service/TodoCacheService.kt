package com.ifmix.api.core.modules.todo.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.TodoService
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Todo Redis 缓存服务（Cache-Aside 策略）。
 * - 读：先查 Redis，miss 则查 DB 并写入 Redis。
 * - 写/删：调用方负责 invalidate。
 * TTL 5 分钟。Redis 不可用时降级为直接走 DB。
 */
@Service
class TodoCacheService(
    private val todoService: TodoService,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val TTL = Duration.ofMinutes(5)

    fun getById(ctx: RequestContext, id: String): TodoDocument {
        return try {
            val key = cacheKey(ctx, id)
            val cached = redisTemplate.opsForValue().get(key)
            if (cached != null) {
                objectMapper.readValue(cached, TodoDocument::class.java)
            } else {
                val doc = todoService.getById(ctx, id)
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(doc), TTL)
                doc
            }
        } catch (_: Exception) {
            // Redis 不可用降级到直查 DB
            todoService.getById(ctx, id)
        }
    }

    fun invalidate(ctx: RequestContext, id: String) {
        try {
            redisTemplate.delete(cacheKey(ctx, id))
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun cacheKey(ctx: RequestContext, id: String) = "todo:${ctx.appId}:$id"
}
