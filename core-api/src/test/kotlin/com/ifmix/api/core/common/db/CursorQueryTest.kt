package com.ifmix.api.core.common.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CursorQueryTest {

    @Test
    fun nullLimitDefaultsTo20() {
        assertThat(CursorQuery().effectiveLimit()).isEqualTo(20)
    }

    @Test
    fun limitCappedAtMax() {
        assertThat(CursorQuery(limit = 500).effectiveLimit()).isEqualTo(100)
    }

    @Test
    fun limitFlooredAtOne() {
        assertThat(CursorQuery(limit = 0).effectiveLimit()).isEqualTo(1)
        assertThat(CursorQuery(limit = -3).effectiveLimit()).isEqualTo(1)
    }

    @Test
    fun nullOrderDefaultsToDesc() {
        assertThat(CursorQuery().effectiveOrder()).isEqualTo(CursorQuery.Order.DESC)
    }

    @Test
    fun explicitOrderKept() {
        assertThat(CursorQuery(order = CursorQuery.Order.ASC).effectiveOrder())
            .isEqualTo(CursorQuery.Order.ASC)
    }
}
