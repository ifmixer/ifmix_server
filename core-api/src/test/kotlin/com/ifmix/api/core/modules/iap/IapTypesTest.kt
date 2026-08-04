package com.ifmix.api.core.modules.iap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class IapTypesTest {

    @Test
    fun statusFromExpiry_returnsActive_whenFuture() {
        val future = Instant.now().plusSeconds(86400)
        assertThat(statusFromExpiry(future)).isEqualTo(SubscriptionState.ACTIVE)
    }

    @Test
    fun statusFromExpiry_returnsExpired_whenPast() {
        val past = Instant.now().minusSeconds(86400)
        assertThat(statusFromExpiry(past)).isEqualTo(SubscriptionState.EXPIRED)
    }

    @Test
    fun statusFromExpiry_returnsActive_whenNull() {
        assertThat(statusFromExpiry(null)).isEqualTo(SubscriptionState.ACTIVE)
    }

    @Test
    fun tierOf_returnsTier_whenMatched() {
        val map = mapOf("premium_monthly" to "PRO", "enterprise_yearly" to "ENTERPRISE")
        assertThat(tierOf("premium_monthly", map)).isEqualTo(com.ifmix.api.core.infra.ratelimit.Tier.PRO)
        assertThat(tierOf("enterprise_yearly", map)).isEqualTo(com.ifmix.api.core.infra.ratelimit.Tier.ENTERPRISE)
    }

    @Test
    fun tierOf_returnsNull_whenNotMatched() {
        val map = mapOf("premium_monthly" to "PRO")
        assertThat(tierOf("unknown_sku", map)).isNull()
    }

    @Test
    fun tierOf_handlesMixedCaseMapValue() {
        val map = mapOf("basic" to "free")
        assertThat(tierOf("basic", map)).isEqualTo(com.ifmix.api.core.infra.ratelimit.Tier.FREE)
    }
}
