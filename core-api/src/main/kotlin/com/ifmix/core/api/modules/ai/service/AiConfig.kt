package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.modules.ai.repo.AiApiKeyRepository
import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.ApiProviders
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.provider
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 模块 bean 装配。
 *
 * AiApiKeyStore 的三个接缝（loadKeys / readCooldowns / markCooldownFn）都以 lambda 注入
 * （不适合直接 @Component 注册），在此处用 sqlClient + StringRedisTemplate 组装。
 *
 * 降级（设计见 docs/design/ai-api-key-pool.md）：Redis 异常 → 视为「未冷却」放行 + 限频 WARN，
 * 不设内存兜底层（重试上界已存在）。注意不要改成「判返回 null」——连接断开时 Spring 抛的是
 * RedisConnectionFailureException（DataAccessException 子类），判空兜不住。
 */
@Configuration
class AiConfig(
    private val sqlClient: KSqlClient,
    private val redis: StringRedisTemplate,
    private val aiApiKeyRepo: AiApiKeyRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 上次降级告警时间（epoch millis）——AtomicLong CAS 限频，避免 Redis 故障时每请求刷 WARN。 */
    private val lastDegradedWarnAtMs = AtomicLong(0L)

    /** 冷却 Redis key（hash tag {cd} 兼容 Redis Cluster 的 MGET 同 slot）。 */
    private fun cooldownKey(keyId: String) = "aikey:{cd}:$keyId"

    @Bean
    fun aiApiKeyStore(
        @Value("\${app.ai.apikey-pool.probe-window:5}") probeWindow: Int,
    ): AiApiKeyStore = AiApiKeyStore(
        loadKeys = {
            // 用全局 sqlClient 加载 Agnes provider 的启用 key（不依赖 ModuleCtx，key 加载是 infra 级操作）。
            // 将来接新 provider 时：新 provider 的 loader 在此并列，各自按 provider 过滤。
            val keys = sqlClient.createQuery(AiApiKey::class) {
                where(table.enabled eq true)
                where(table.provider eq ApiProviders.AGNES)
                select(table)
            }.execute()
            keys.map { k ->
                AiApiKeyStore.AiApiKeyDoc(
                    id = k.id.toString(),
                    key = k.key,
                )
            }
        },
        readCooldowns = { keyIds ->
            if (keyIds.isEmpty()) {
                emptyList()
            } else {
                try {
                    val values = redis.opsForValue().multiGet(keyIds.map { cooldownKey(it) }).orEmpty()
                    List(keyIds.size) { i -> values.getOrNull(i) != null }
                } catch (e: DataAccessException) {
                    warnDegraded("MGET cooldowns", e)
                    List(keyIds.size) { false }
                }
            }
        },
        markCooldownFn = { keyId, cooldownSec, reason ->
            try {
                redis.opsForValue().set(cooldownKey(keyId), reason, Duration.ofSeconds(cooldownSec))
            } catch (e: DataAccessException) {
                warnDegraded("SET cooldown", e)
            }
        },
        probeWindow = probeWindow,
    )

    private fun warnDegraded(op: String, e: Exception) {
        val now = System.currentTimeMillis()
        val last = lastDegradedWarnAtMs.get()
        if (now - last >= TimeUnit.SECONDS.toMillis(DEGRADED_WARN_INTERVAL_SEC) &&
            lastDegradedWarnAtMs.compareAndSet(last, now)
        ) {
            log.warn("AI key-pool degraded: redis {} failed (fail-open, cooldown disabled until redis recovers): {}",
                op, e.toString())
        }
    }

    companion object {
        private const val DEGRADED_WARN_INTERVAL_SEC = 30L
    }
}
