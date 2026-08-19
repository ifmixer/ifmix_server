package com.ifmix.api.core.infra.mybatis

import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import java.sql.*
import java.util.UUID

/**
 * UUID ↔ PG uuid 类型处理器。
 * PostgreSQL JDBC 驱动默认将 uuid 列映射为 java.util.UUID，
 * 此 TypeHandler 确保兼容并覆盖 null 行为。
 */
class UuidTypeHandler : BaseTypeHandler<UUID?>() {

    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: UUID?, jdbcType: JdbcType?) {
        ps.setObject(i, parameter, Types.OTHER)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): UUID? {
        val uuid = rs.getObject(columnName)
        return if (uuid is UUID) uuid else null
    }

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): UUID? {
        val uuid = rs.getObject(columnIndex)
        return if (uuid is UUID) uuid else null
    }

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): UUID? {
        val uuid = cs.getObject(columnIndex)
        return if (uuid is UUID) uuid else null
    }
}
