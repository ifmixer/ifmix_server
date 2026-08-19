package com.ifmix.api.core.common.ai

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.ScanResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.client.ChatClient
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** SpringAiScanRunner test — mock keystore + mock ChatClient. */
class SpringAiScanRunnerTest {

    private val ctx = RequestContext(appId = "test-app", installId = "install-1")

    @Test
    fun `no keys returns FAILED status`() {
        runBlocking {
            val redis = mock(org.springframework.data.redis.core.StringRedisTemplate::class.java)
            val mockStore = AgnesKeyStore(
                redis = redis,
                now = { Instant.now() },
                loadKeys = { emptyList() },
            )
            val mockFactory = object : AgnesChatClientFactory("https://test.com", "gpt-4o") {
                override fun forKey(apiKey: String, model: String): ChatClient {
                    throw IllegalStateException("should not be called")
                }
            }
            val runner = SpringAiScanRunner(mockFactory, mockStore, listOf("llama"))

            val result = runner.run(ctx, "https://example.com/photo.png")

            assertThat(result.status).isEqualTo(ScanResult.Status.FAILED)
            assertThat(result.errorMessage).isNotNull
        }
    }

    @Test
    fun `all preDeduct fail returns FAILED status`() {
        runBlocking {
            val keyDoc = AgnesKeyEntity().apply {
                id = "key-1"
                key = "sk-test"
                rateLimit = 0L
            }

            val states = mutableMapOf(
                "key-1" to AgnesKeyStore.KeyState(keyDoc, remaining = 0),
            )

            val mockStore = object : AgnesKeyStore(
                redis = mock(org.springframework.data.redis.core.StringRedisTemplate::class.java),
                now = { Instant.now() },
                loadKeys = { emptyList() },
            ) {
                override fun init(): MutableMap<String, AgnesKeyStore.KeyState> = states
                override fun weightedPick(states: Map<String, AgnesKeyStore.KeyState>): String? = "key-1"
                override fun preDeduct(states: MutableMap<String, AgnesKeyStore.KeyState>, keyId: String): Boolean {
                    states[keyId]?.remaining = -1
                    return false
                }
            }

            val mockFactory = object : AgnesChatClientFactory("https://test.com", "gpt-4o") {
                override fun forKey(apiKey: String, model: String): ChatClient {
                    throw IllegalStateException("should not be called when preDeduct fails")
                }
            }

            val runner = SpringAiScanRunner(mockFactory, mockStore, listOf("llama"))

            val result = runner.run(ctx, "https://example.com/photo.png")

            assertThat(result.status).isEqualTo(ScanResult.Status.FAILED)
        }
    }
}
