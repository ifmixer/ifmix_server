package com.ifmix.api.core.infra.exposed

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import java.time.Instant
import java.util.UUID

/** 构造 deleted_at IS NULL 条件 */
fun notDeleted(column: Column<Instant?>): Op<Boolean> = column.isNull()

/** 构造 app_id = appId 条件 */
fun appScoped(column: Column<UUID>, appId: UUID): Op<Boolean> = column eq appId
