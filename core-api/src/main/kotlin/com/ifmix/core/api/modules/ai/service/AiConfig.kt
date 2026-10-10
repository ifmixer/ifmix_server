package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.ApiProviders
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.provider
import com.ifmix.core.api.entity.ai.updatedAt
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 模块 bean 装配。
 *
 * AiApiKeyStore 的四个接缝（loadKeys / readCooldowns / markCooldownFn / disableKeyFn）都以 lambda 注入
 * （不适合直接 @Component 注册），在此处用 sqlClient + StringRedisTemplate 组装。
 *
 * 降级（设计见 docs/design/ai/api-key-pool.md）：Redis 异常 → 视为「未冷却」放行 + 限频 WARN，
 * 不设内存兜底层（重试上界已存在）。注意不要改成「判返回 null」——连接断开时 Spring 抛的是
 * RedisConnectionFailureException（DataAccessException 子类），判空兜不住。
 *
 * 禁用（docs/design/ai/api-key-disable-and-probe-skip.md）：类型化 401/403
 * 的永久禁用是 best-effort 副作用——DB 写失败只限频 WARN、Redis 1h 冷却仍在、300s 后列表重载移出，
 * 因此 **任何异常都不得逃出 disableKeyFn**（catch Exception 整体收敛而非按类型列举——
 * Jimmer 异常不经 Spring 翻译、不是 DataAccessException，按类型列举兜不住）。
 * 写成功后 [AiApiKeyStore] 会把 key 从本地列表立即剔除，不再等 300s 重载（该窗口原先只靠 Redis 冷却兜）。
 */
@Configuration
class AiConfig(
    private val sqlClient: KSqlClient,
    private val redis: StringRedisTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 上次降级告警时间（epoch millis）——AtomicLong CAS 限频，避免 Redis 故障时每请求刷 WARN。 */
    private val lastDegradedWarnAtMs = AtomicLong(0L)

    /** 冷却 Redis key（hash tag {cd} 兼容 Redis Cluster 的 MGET 同 slot）。 */
    private fun cooldownKey(keyId: String) = "aikey:{cd}:$keyId"

    /** DeepResearch 后台任务 executor（Spring 管理的虚拟线程池，设计 §8.1：非散落裸线程）。 */
    @Bean("deepResearchExecutor")
    fun deepResearchExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

    /** Scan 后台任务 executor：事务提交后运行，避免 AI 阻塞 GraphQL 请求。 */
    @Bean("scanExecutor")
    fun scanExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

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
                    warnDegraded("MGET cooldowns", "redis failed (fail-open, cooldown disabled until redis recovers)", e)
                    List(keyIds.size) { false }
                }
            }
        },
        markCooldownFn = { keyId, cooldownSec, reason ->
            try {
                redis.opsForValue().set(cooldownKey(keyId), reason, Duration.ofSeconds(cooldownSec))
            } catch (e: DataAccessException) {
                warnDegraded("SET cooldown", "redis failed (fail-open, cooldown disabled until redis recovers)", e)
            }
        },
        disableKeyFn = { keyId ->
            // 失效 key 的永久禁用（不可逆，需人工恢复）。best-effort 副作用：任何异常在此收敛
            // （Jimmer 异常不经 Spring 翻译、不是 DataAccessException，catch Exception 整体兜住），
            // 绝不向 runner 逃逸导致扫描失败；DB 故障时 Redis 1h 冷却 + 300s 列表重载仍能把 key 移出。
            // 返回值语义：true = DB 已确认该 key 不在 enabled 集合（写成功；affected=0 的重复禁用同样是"已不在"），
            // AiApiKeyStore 据此把 key 从本地列表立即剔除——不再依赖下一次列表重载。
            try {
                val affected = sqlClient.createUpdate(AiApiKey::class) {
                    where(table.id eq UUID.fromString(keyId))
                    // 只匹配「还是启用状态」的行：加了这一条，重复禁用才是真幂等。
                    // 否则 updated_at 每次都变（虽然 enabled 不变），UPDATE 永远 affected=1——
                    // updated_at 会漂移到"最后一次重试禁用"的时刻（排障时误判禁用时间），
                    // 并且每次都打一条 WARN，看着像刚刚才被误杀一次。
                    where(table.enabled eq true)
                    set(table.enabled, false)
                    // 必须显式写 updated_at：表上没有 ON UPDATE trigger，Jimmer 的局部更新只写 set 的列。
                    // 不写的话 disabled 行的 updated_at 会一直停在插入时的时间点，排障时无法判断"何时被自动禁用"
                    // （2026-10-10 之前的禁用行就是这么来的：只能靠滚动日志推断时间）。
                    set(table.updatedAt, Instant.now())
                }.execute()
                if (affected > 0) {
                    log.warn("AI API key disabled (enabled=false). keyId={} — 需运营确认是否误杀，恢复：改回 enabled=true 或删除行", keyId)
                } else {
                    log.info("AI API key disable was a no-op (already disabled or deleted). keyId={}", keyId)
                }
                true
            } catch (e: Exception) {
                warnDegraded("disable key (PG enabled=false)", "db write failed (Redis 1h cooldown still active; retry is idempotent)", e)
                false
            }
        },
        probeWindow = probeWindow,
    )

    /**
     * key 池降级限频 WARN（Redis / DB 共用同一限频槽——同属「key 池降级」告警）。
     * reason 必须描述**实际故障与后果**（硬编码 "redis failed" 会在 DB 故障时误导排查），
     * 由调用点按 op 各自传入。
     */
    internal fun warnDegraded(op: String, reason: String, e: Exception) {
        val now = System.currentTimeMillis()
        val last = lastDegradedWarnAtMs.get()
        if (now - last >= TimeUnit.SECONDS.toMillis(DEGRADED_WARN_INTERVAL_SEC) &&
            lastDegradedWarnAtMs.compareAndSet(last, now)
        ) {
            log.warn("AI key-pool degraded: {} op={} error={}", reason, op, e.toString())
        }
    }

    companion object {
        private const val DEGRADED_WARN_INTERVAL_SEC = 30L
    }
}
