package com.ifmix.core.api.modules.project

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNegative
import assertk.assertions.isPositive
import assertk.assertions.isTrue
import com.ifmix.core.api.modules.project.handler.CloudConfigItemAggHandler
import org.junit.jupiter.api.Test

/** otaVersion 逐段数值比较（云配置版本门槛用；otaVersion 形如 `1-23-3`，`.` 与 `-` 都作分隔）。 */
class CloudConfigVersionTest {

    private fun compare(a: String, b: String) = CloudConfigItemAggHandler.compareOtaVersion(a, b)

    @Test
    fun `multi segment numeric compare`() {
        assertThat(compare("1.0.10", "1.0.9")).isPositive()
        assertThat(compare("1.0.9", "1.0.10")).isNegative()
        assertThat(compare("1.0.8", "1.0.8")).isEqualTo(0)
    }

    @Test
    fun `missing segments padded with zero`() {
        assertThat(compare("1.0", "1.0.0")).isEqualTo(0)
        assertThat(compare("1", "1.0.1")).isNegative()
        assertThat(compare("2", "1.9.9")).isPositive()
    }

    @Test
    fun `dash separated ota version format`() {
        assertThat(compare("1-23-3", "1-23-3")).isEqualTo(0)
        assertThat(compare("1-24-0", "1-23-3")).isPositive()
        assertThat(compare("1-22-9", "1-23-0")).isNegative()
    }

    @Test
    fun `suffix keeps leading digits only`() {
        assertThat(compare("1.0.8-rc1", "1.0.8")).isEqualTo(0)
        assertThat(compare("1.0.8-rc1", "1.0.9")).isNegative()
    }

    @Test
    fun `non numeric segment counts as zero`() {
        assertThat(compare("1.0.beta", "1.0.0")).isEqualTo(0)
        assertThat(compare("1.0.beta", "1.0.1")).isNegative()
    }
}
