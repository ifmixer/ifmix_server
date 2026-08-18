package com.ifmix.api.core.infra.db

import com.ifmix.api.core.dto.common.CursorQueryInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CursorQueryInputTest {

    @Test
    fun nullLimitDefaultsTo20() {
        assertThat(CursorQueryInput().effectiveLimit()).isEqualTo(20)
    }

    @Test
    fun limitCappedAtMax() {
        assertThat(CursorQueryInput(limit = 500).effectiveLimit()).isEqualTo(100)
    }

    @Test
    fun limitFlooredAtOne() {
        assertThat(CursorQueryInput(limit = 0).effectiveLimit()).isEqualTo(1)
        assertThat(CursorQueryInput(limit = -3).effectiveLimit()).isEqualTo(1)
    }

    @Test
    fun defaultsToIdDescending() {
        val input = CursorQueryInput()
        assertThat(input.effectiveSortBy()).isEqualTo("id")
        assertThat(input.effectiveOrder()).isEqualTo(com.ifmix.api.core.dto.common.SortOrder.DESC)
    }
}
