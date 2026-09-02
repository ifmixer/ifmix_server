package com.ifmix.core.api.infra.jimmer

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * 读写分离路由：
 * - 非只读事务 → writer
 * - @Transactional(readOnly=true) → reader
 */
class ReadWriteRoutingDataSource(
    private val writerDs: DataSource,
    private val readerDs: DataSource,
) : AbstractRoutingDataSource() {

    companion object {
        private const val WRITER = "writer"
        private const val READER = "reader"
    }

    init {
        setTargetDataSources(mapOf<Any, Any>(WRITER to writerDs, READER to readerDs))
        setDefaultTargetDataSource(writerDs)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any {
        val isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        return if (isReadOnly) READER else WRITER
    }
}
