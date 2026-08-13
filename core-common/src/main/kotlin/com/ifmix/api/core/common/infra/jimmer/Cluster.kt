package com.ifmix.api.core.common.infra.jimmer

import com.zaxxer.hikari.HikariDataSource
import org.babyfish.jimmer.sql.kt.KSqlClient

class Cluster(
    val id: String,
    val writerDataSource: HikariDataSource,
    val readerDataSource: HikariDataSource,
    val writerClient: KSqlClient,
    val readerClient: KSqlClient,
) {
    fun sql(preferReader: Boolean): KSqlClient =
        if (preferReader) readerClient else writerClient

    fun close() {
        writerDataSource.close()
        readerDataSource.close()
    }
}
