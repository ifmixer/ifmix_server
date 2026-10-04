package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.modules.ai.service.AiConfig
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.KExecutable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.InvalidDataAccessResourceUsageException
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * 锁定「任何异常不得逃出 disableKeyFn」不变量（设计见
 * docs/superpowers/specs/2026-10-04-ai-key-disable-and-probe-skip-design.md）：
 * 禁用是扫描路径上的 best-effort 副作用，DB 故障绝不能让扫描失败。
 * Jimmer 异常不经 Spring 翻译、不是 DataAccessException，按类型列举兜不住——
 * 实现是 catch (Exception) 整体收敛，本测试用纯单测（stub [KSqlClient]，不依赖 DB）锁死。
 *
 * disableKeyFn 由 [AiConfig.aiApiKeyStore] 组装返回的 store 的接缝暴露，
 * 通过 store.disableKey 间接调用。stub 的 [KSqlClient.createUpdate] 返回一个
 * [KExecutable] mock，其 execute() 抛各类异常，断言 disableKey 不外抛（不外抛即通过）。
 */
class AiConfigDisableKeyFnTest {

    private val keyId = UUID.randomUUID().toString()

    private fun newConfig(): Triple<AiConfig, KSqlClient, KExecutable<Int>> {
        val sqlClient = mock<KSqlClient>()
        val redis = mock<StringRedisTemplate>()
        val exec = mock<KExecutable<Int>>()
        whenever(sqlClient.createUpdate<AiApiKey>(any(), any())).thenReturn(exec)
        // doReturn 风格（而非 whenever(exec.execute())——后者本身会真实调用一次 execute()，
        // 污染后续 never() 验证的调用计数）
        doReturn(1).whenever(exec).execute()
        return Triple(AiConfig(sqlClient, redis), sqlClient, exec)
    }

    @Test
    fun `update throwing RuntimeException does not escape disableKey`() {
        val (config, sqlClient, exec) = newConfig()
        doThrow(RuntimeException("db down")).whenever(exec).execute()
        // 不外抛即通过：禁用是 best-effort，DB 故障由 Redis 1h 冷却 + 300s 列表重载兜底
        config.aiApiKeyStore(probeWindow = 5).disableKey(keyId)
        verify(exec).execute(null)
    }

    @Test
    fun `update throwing a DataAccessException subclass does not escape disableKey`() {
        val (config, sqlClient, exec) = newConfig()
        // 具体子类 InvalidDataAccessResourceUsageException（既有 DataAccessException 实现）：
        // catch Exception 收敛对它是子类、是父类一视同仁
        doThrow(InvalidDataAccessResourceUsageException("db down")).whenever(exec).execute()
        config.aiApiKeyStore(probeWindow = 5).disableKey(keyId)
        verify(exec).execute(null)
    }

    @Test
    fun `update throwing IllegalArgumentException does not escape disableKey`() {
        val (config, sqlClient, exec) = newConfig()
        doThrow(IllegalArgumentException("bad key id")).whenever(exec).execute()
        config.aiApiKeyStore(probeWindow = 5).disableKey(keyId)
        verify(exec).execute(null)
    }

    @Test
    fun `normal path reports affected count`() {
        val (config, sqlClient, exec) = newConfig()
        // affected=1（stub 默认）→ 实现内部打 WARN；不外抛即通过
        config.aiApiKeyStore(probeWindow = 5).disableKey(keyId)
        verify(exec).execute()
    }
}
