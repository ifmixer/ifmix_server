package com.ifmix.api.core.infra.mybatis

import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import org.postgresql.util.PGobject
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * PostgreSQL JSONB ↔ 任意类型的 TypeHandler 基类。
 * 每个领域模型写一行子类即可。
 */
abstract class JsonbTypeHandler<T : Any>(private val type: Class<T>) : BaseTypeHandler<T>() {

    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: T, jdbcType: JdbcType?) {
        val pgObj = PGobject().apply {
            this.type = "jsonb"
            value = JsonbUtil.serialize(parameter)
        }
        ps.setObject(i, pgObj)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): T? =
        JsonbUtil.deserialize(rs.getString(columnName), type)

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): T? =
        JsonbUtil.deserialize(rs.getString(columnIndex), type)

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): T? =
        JsonbUtil.deserialize(cs.getString(columnIndex), type)
}
