package com.ifmix.api.core.common.jimmer.cluster

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionSynchronizationManager

class ReadWriteRoutingDataSourceTest {

    @Test
    fun `routes to writer when not in read-only transaction`() {
        val writerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("writer").build()
        val readerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("reader").build()
        val routing = ReadWriteRoutingDataSource(writerDs, readerDs)

        // 不在事务中 → 默认走 writer
        val key = routing.determineCurrentLookupKey()
        assertThat(key).isEqualTo("writer")

        writerDs.shutdown()
        readerDs.shutdown()
    }

    @Test
    fun `routes to reader when in read-only transaction`() {
        val writerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("writer2").build()
        val readerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("reader2").build()
        val routing = ReadWriteRoutingDataSource(writerDs, readerDs)

        // 模拟 read-only 事务
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        try {
            val key = routing.determineCurrentLookupKey()
            assertThat(key).isEqualTo("reader")
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        }

        writerDs.shutdown()
        readerDs.shutdown()
    }
}
