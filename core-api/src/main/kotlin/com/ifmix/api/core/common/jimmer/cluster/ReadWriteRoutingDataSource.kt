package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * 读写分离路由：
 * - @Transactional(readOnly = true) → reader
 * - 其他 → writer
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

    override public fun determineCurrentLookupKey(): Any {
        val isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        return if (isReadOnly) READER else WRITER
    }
}
