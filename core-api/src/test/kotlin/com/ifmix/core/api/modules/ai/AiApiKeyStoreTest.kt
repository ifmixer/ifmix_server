package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.contains
import com.ifmix.core.api.modules.ai.service.AiApiKeyStore
import org.junit.jupiter.api.Test

/**
 * AI API Key 池纯逻辑单测（Redis 读写用内存 fake lambda，见 docs/design/ai-api-key-pool.md）。
 */
class AiApiKeyStoreTest {

    private class Fake(keyIds: List<String>, probeWindow: Int = 8, cursorSeed: Long = 0) {
        val cooled = mutableSetOf<String>()
        val marks = mutableListOf<Triple<String, Long, String>>()
        val mgetSizes = mutableListOf<Int>()
        var loadCount = 0

        val store = AiApiKeyStore(
            loadKeys = {
                loadCount++
                keyIds.map { AiApiKeyStore.AiApiKeyDoc(it, "key-$it") }
            },
            readCooldowns = { ids ->
                mgetSizes.add(ids.size)
                ids.map { it in cooled }
            },
            markCooldownFn = { id, sec, reason ->
                marks.add(Triple(id, sec, reason))
                cooled.add(id)
            },
            probeWindow = probeWindow,
            cursorSeed = cursorSeed,
        )
    }

    @Test
    fun `pick round-robins over the whole pool`() {
        val fake = Fake((1..5).map { "k$it" })
        val picked = (1..5).map { fake.store.pick()!!.id }
        // 游标连续推进，连续 5 次覆盖全部 key
        assertThat(picked.distinct()).hasSize(5)
    }

    @Test
    fun `key list is shuffled without losing keys`() {
        val fake = Fake((1..100).map { "k$it" }, probeWindow = 8)
        val picked = (1..100).map { fake.store.pick()!!.id }
        // 打乱只影响顺序，不能丢 key：100 次连续 pick 覆盖全部 100 个
        assertThat(picked.distinct()).hasSize(100)
    }

    @Test
    fun `cooled keys are skipped and marks carry reason`() {
        val fake = Fake(listOf("a", "b", "c"))
        fake.store.markCooldown("a", 60, "429")
        val picked = (1..6).map { fake.store.pick()!!.id }
        assertThat(picked).doesNotContain("a")
        assertThat(fake.marks.contains(Triple("a", 60L, "429"))).isTrue()
    }

    @Test
    fun `all candidates cooled returns null`() {
        val fake = Fake(listOf("a", "b"))
        fake.store.markCooldown("a", 60, "429")
        fake.store.markCooldown("b", 60, "429")
        assertThat(fake.store.pick()).isNull()
    }

    @Test
    fun `probe window bounds cooldown reads`() {
        val fake = Fake((1..100).map { "k$it" }, probeWindow = 8)
        repeat(10) { fake.store.pick() }
        // 每次pick只读一个探测窗口，而不是整个池子
        assertThat(fake.mgetSizes.all { it == 8 }).isTrue()
    }

    @Test
    fun `probe window is clamped to minimum 1`() {
        val fake = Fake(listOf("a", "b", "c"), probeWindow = 0)
        val picked = (1..3).map { fake.store.pick()!!.id }
        // 误配 0/负数时 clamp 到 1：每次 pick 只探测游标处 1 个 key，不因窗口为空而恒 null
        assertThat(fake.mgetSizes.all { it == 1 }).isTrue()
        assertThat(picked.distinct()).hasSize(3)
    }

    @Test
    fun `empty pool yields null and isEmpty`() {
        val fake = Fake(emptyList())
        assertThat(fake.store.pick()).isNull()
        assertThat(fake.store.isEmpty()).isTrue()
    }

    @Test
    fun `key list is cached within ttl`() {
        val fake = Fake(listOf("a", "b"))
        repeat(3) { fake.store.pick() }
        // TTL 内多次 pick 只触发一次 DB 加载
        assertThat(fake.loadCount).isEqualTo(1)
    }

    @Test
    fun `cursor seed determines starting position deterministically`() {
        val fake = Fake((1..10).map { "k$it" }, probeWindow = 3, cursorSeed = 7)
        val order = fake.store.currentKeys() // 触发加载，拿到打乱后的内部顺序
        // seed=7 → 从位置 7 开始；probe=3 → 窗口 [7,8,9]，pick 返回窗口内第一个未冷却者
        assertThat(fake.store.pick()!!.id).isEqualTo(order[7].id)
        assertThat(fake.store.pick()!!.id).isEqualTo(order[8].id)
        // 环形回绕：第三次窗口 [9,0,1]，第四次 [0,1,2]
        assertThat(fake.store.pick()!!.id).isEqualTo(order[9].id)
        assertThat(fake.store.pick()!!.id).isEqualTo(order[0].id)
    }
}
