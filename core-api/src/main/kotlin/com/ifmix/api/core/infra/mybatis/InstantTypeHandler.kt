package com.ifmix.api.core.infra.mybatis

import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import java.sql.*
import java.time.Instant

/**
 * Instant ↔ PG timestamptz 类型处理器。
 * PostgreSQL JDBC 驱动将 timestamptz 列映射为 OffsetDateTime；
 * 此处转换为 java.time.Instant 与 jOOQ 现有行为保持一致。
 */
class InstantTypeHandler : BaseTypeHandler<Instant?>() {

    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: Instant?, jdbcType: JdbcType?) {
        ps.setObject(i, parameter, Types.TIMESTAMP_WITH_TIMEZONE)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): Instant? {
        val dt = rs.getObject(columnName, java.time.OffsetDateTime::class.java)
        return dt?.toInstant()
    }

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): Instant? {
        val dt = rs.getObject(columnIndex, java.time.OffsetDateTime::class.java)
        return dt?.toInstant()
    }

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): Instant? {
        val dt = cs.getObject(columnIndex, java.time.OffsetDateTime::class.java)
        return dt?.toInstant()
    }
}
