package com.ifmix.api.core.infra.jooq

import org.jooq.Table
import org.jooq.TableField
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 实例化 CrudRepoOps 的工厂。
 * 每个 Repository 在初始化时绑定 table / idField / appIdField / type / deletedAtField，
 * 之后方法只需传 ctx + 业务参数。
 */
@Component
class CrudRepoOpsFactory {

    fun <T : Any> create(
        table: Table<*>,
        idField: TableField<*, *>,
        appIdField: TableField<*, *>? = null,
        type: Class<T>,
        deletedAtField: TableField<*, *>? = null,
    ): CrudRepoOps<T> = CrudRepoOps(table, idField, appIdField, type, deletedAtField)
}
