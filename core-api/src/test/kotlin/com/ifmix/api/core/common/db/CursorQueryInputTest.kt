package com.ifmix.api.core.infra.db

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
        assertThat(input.sortBy).isNull()
        assertThat(input.order).isNull()
        assertThat(input.effectiveSortBy()).isEqualTo("createdAt")
        assertThat(input.effectiveOrder()).isEqualTo(CursorQueryInput.Order.DESC)
    }
}
