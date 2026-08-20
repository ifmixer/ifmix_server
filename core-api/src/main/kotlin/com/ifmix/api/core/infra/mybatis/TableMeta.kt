package com.ifmix.api.core.infra.mybatis

import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.SqlTable
import java.time.Instant
import java.util.UUID

/**
 * 表元数据 — 由 CrudRepoTemplate 使用，定义通用 CRUD 需要的列引用。
 */
data class TableMeta(
    val table: SqlTable,
    val id: SqlColumn<UUID>,
    val appId: SqlColumn<UUID>?,        // null = 全局实体（无租户隔离）
    val deletedAt: SqlColumn<Instant>?, // null = 硬删除
    val allColumns: List<SqlColumn<*>>,
)
