package com.ifmix.api.core.common.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Random

/** AgnesKeyStore 的单元测试：加权挑选、配额管理、冷却机制。 */
class AgnesKeyStoreTest {

    private lateinit var store: AgnesKeyStore
    private var nowValue = Instant.parse("2026-07-27T12:00:00Z")
    private val deterministicRng = Random(42)

    @BeforeEach
    fun setUp() {
        // mock StringRedisTemplate
        val redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val ops = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations::class.java) as org.springframework.data.redis.core.ValueOperations<String, String>
        org.mockito.Mockito.`lenient`().whenever(redis.opsForValue()).thenReturn(ops)

        // 构建测试用的 key 文档
        val keys = listOf(
            makeKey("key-1", "PRIMARY", 10, 3600, emptyList()),
            makeKey("key-2", "FALLBACK", 20, 3600, emptyList()),
            makeKey("key-3", "HOTSPARE", 5, 3600, emptyList()),
        )

        store = AgnesKeyStore(
            redis = redis,
            now = { nowValue },
            rng = deterministicRng,
            loadKeys = { keys },
        )
    }

    @Test
    fun `weightedPick selects from available keys by remaining quota`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 10),
            "key-2" to AgnesKeyStore.KeyState(makeKeyDoc("key-2", 20), 20),
            "key-3" to AgnesKeyStore.KeyState(makeKeyDoc("key-3", 5), 5),
        )

        val picked = store.weightedPick(states)
        assertThat(picked).isIn("key-1", "key-2", "key-3")
    }

    @Test
    fun `weightedPick excludes keys with zero remaining`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 0),
            "key-2" to AgnesKeyStore.KeyState(makeKeyDoc("key-2", 20), 20),
        )

        val picked = store.weightedPick(states)
        assertThat(picked).isEqualTo("key-2")
    }

    @Test
    fun `weightedPick excludes keys in cooling`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 10, Instant.parse("2027-01-01T00:00:00Z")),
            "key-2" to AgnesKeyStore.KeyState(makeKeyDoc("key-2", 20), 20, null),
        )

        val picked = store.weightedPick(states)
        assertThat(picked).isEqualTo("key-2")
    }

    @Test
    fun `weightedPick returns null when no keys available`() {
        val states = mutableMapOf<String, AgnesKeyStore.KeyState>()
        val picked = store.weightedPick(states)
        assertThat(picked).isNull()
    }

    @Test
    fun `preDeduct decrements and returns true when remaining positive`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 5),
        )

        val result = store.preDeduct(states, "key-1")
        assertThat(result).isTrue()
        assertThat(states["key-1"]!!.remaining).isEqualTo(4)
    }

    @Test
    fun `preDeduct marks cooling when remaining goes below 0`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10, windowSec = 300), 0),
        )

        val result = store.preDeduct(states, "key-1")
        assertThat(result).isFalse()
        assertThat(states["key-1"]!!.remaining).isEqualTo(-1)
        assertThat(states["key-1"]!!.coolingUntil).isNotNull
        assertThat(states["key-1"]!!.coolingUntil!!).isAfter(nowValue)
    }

    @Test
    fun `preDeduct then retry picks another key when first exhausted`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 0),
            "key-2" to AgnesKeyStore.KeyState(makeKeyDoc("key-2", 20), 20),
        )

        val r1 = store.preDeduct(states, "key-1")
        assertThat(r1).isFalse()

        val picked = store.weightedPick(states)
        assertThat(picked).isEqualTo("key-2")
    }

    @Test
    fun `release corrects negative remaining back to 0`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), -1),
        )

        store.release(states, "key-1")
        assertThat(states["key-1"]!!.remaining).isEqualTo(0)
    }

    @Test
    fun `markUnavailable sets coolingUntil`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 5),
        )

        store.markUnavailable(states, "key-1", 600)
        assertThat(states["key-1"]!!.coolingUntil).isNotNull
        assertThat(states["key-1"]!!.coolingUntil!!).isAfter(nowValue)
    }

    @Test
    fun `clearCooling removes cooling state`() {
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(makeKeyDoc("key-1", 10), 5, Instant.parse("2027-01-01T00:00:00Z")),
        )

        store.clearCooling(states, "key-1")
        assertThat(states["key-1"]!!.coolingUntil).isNull()
    }

    @Test
    fun `weightedPick excludes keys with DB unavailableUntil not yet expired`() {
        val future = Instant.parse("2027-01-01T00:00:00Z")
        val doc = makeKeyDoc("key-1", 10, unavailableUntil = future)
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(doc, 10),
        )

        val picked = store.weightedPick(states)
        assertThat(picked).isNull()
    }

    @Test
    fun `weightedPick includes keys with DB unavailableUntil already expired`() {
        val past = Instant.parse("2020-01-01T00:00:00Z")
        val doc = makeKeyDoc("key-1", 10, unavailableUntil = past)
        val states = mutableMapOf(
            "key-1" to AgnesKeyStore.KeyState(doc, 10),
        )

        val picked = store.weightedPick(states)
        assertThat(picked).isEqualTo("key-1")
    }

    // ---- helpers ----

    private fun makeKey(id: String, type: String, rateLimit: Long, windowSec: Long, models: List<String>): AgnesKeyEntity {
        return AgnesKeyEntity().apply {
            this.id = id
            appId = "test-app"
            this.key = "sk-test-$id"
            this.type = type
            this.rateLimit = rateLimit
            this.windowSec = windowSec
            this.models = models.joinToString(",")
        }
    }

    private fun makeKeyDoc(
        id: String,
        rateLimit: Long = 10,
        windowSec: Long = 3600,
        coolingUntil: Instant? = null,
        unavailableUntil: Instant? = null,
        models: String? = null,
    ): AgnesKeyEntity {
        return AgnesKeyEntity().apply {
            this.id = id
            this.rateLimit = rateLimit
            this.windowSec = windowSec
            this.unavailableUntil = unavailableUntil
            this.models = models
        }
    }
}
