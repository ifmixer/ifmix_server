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
        val disabled = mutableListOf<String>()
        val mgetSizes = mutableListOf<Int>()
        val mgetIdSets = mutableListOf<List<String>>()
        var loadCount = 0

        val store = AiApiKeyStore(
            loadKeys = {
                loadCount++
                keyIds.map { AiApiKeyStore.AiApiKeyDoc(it, "key-$it") }
            },
            readCooldowns = { ids ->
                mgetSizes.add(ids.size)
                mgetIdSets.add(ids.toList())
                ids.map { it in cooled }
            },
            markCooldownFn = { id, sec, reason ->
                marks.add(Triple(id, sec, reason))
                cooled.add(id)
            },
            disableKeyFn = { id ->
                disabled.add(id)
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

    @Test
    fun `all-cooled pick skips the whole probe window instead of overlapping`() {
        // 全冷却时净推进 probe（1 + (probe-1)）：每次 pick 的窗口互不重叠，
        // 而非改前「窗口起点差 1、重叠 4/5」的反复重探。
        val fake = Fake((1..20).map { "k$it" }, probeWindow = 5, cursorSeed = 0)
        val order = fake.store.currentKeys().map { it.id } // 拿到打乱后的内部顺序
        (1..20).forEach { fake.store.markCooldown(order[it - 1], 60, "429") }
        repeat(4) { assertThat(fake.store.pick()).isNull() }
        // 4 次全冷却：窗口 [0..4] / [5..9] / [10..14] / [15..19]，恰好覆盖全池
        val expectedWindows = (0 until 4).map { windowStart ->
            (0 until 5).map { order[(windowStart * 5 + it) % 20] }.toSet()
        }
        assertThat(fake.mgetIdSets.map { it.toSet() }).isEqualTo(expectedWindows)
    }

    @Test
    fun `hit after fully cooled segment needs probe hops not probe-per-window`() {
        // 设计示例：池位置 0..9 全冷却、位置 10 起正常，probe=5（cursorSeed=0）。
        // 改前 4 次 attempt 窗口 [0..4]..[3..7] 重叠，爬不出 10 个冷却 key；
        // 改后 attempt1 [0..4] 全冷却 → +5，attempt2 [5..9] 全冷却 → +5，attempt3 [10..14] 命中位置 10。
        // 池加载时 shuffled，按 currentKeys() 实际顺序取目标。
        val fake = Fake((1..15).map { "k$it" }, probeWindow = 5, cursorSeed = 0)
        val order = fake.store.currentKeys().map { it.id }
        order.slice(0 until 10).forEach { fake.store.markCooldown(it, 60, "429") }
        assertThat(fake.store.pick()).isNull()
        assertThat(fake.store.pick()).isNull()
        assertThat(fake.store.pick()!!.id).isEqualTo(order[10])
        // 命中后游标净 +1，回到常规轮询
        assertThat(fake.store.pick()!!.id).isEqualTo(order[11])
    }

    @Test
    fun `probe=1 degrades to legacy behavior on all-cooled`() {
        // probe=1（clamp 下限或 n=1）：全冷却时额外 +0，退化为改前「每次 pick 只推进 1 步」。
        val fake = Fake((1..5).map { "k$it" }, probeWindow = 1, cursorSeed = 0)
        val order = fake.store.currentKeys().map { it.id }
        order.slice(0 until 3).forEach { fake.store.markCooldown(it, 60, "429") }
        assertThat(fake.store.pick()).isNull() // 窗口 [0] 全冷却
        assertThat(fake.store.pick()).isNull() // 窗口 [1]
        assertThat(fake.store.pick()).isNull() // 窗口 [2]
        assertThat(fake.store.pick()!!.id).isEqualTo(order[3])
    }

    @Test
    fun `disableKey delegates to disableKeyFn without touching cooldown`() {
        val fake = Fake(listOf("a", "b"))
        fake.store.disableKey("a")
        assertThat(fake.disabled).contains("a")
        assertThat(fake.marks).hasSize(0)
        assertThat(fake.cooled).doesNotContain("a")
    }
}
