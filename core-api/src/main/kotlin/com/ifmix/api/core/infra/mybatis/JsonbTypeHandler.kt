package com.ifmix.api.core.infra.mybatis

import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import org.postgresql.util.PGobject
import java.sql.*

/**
 * String ↔ PG jsonb 类型处理器。
 * 将数据库 JSONB 列读写为 Jackson 序列化后的 JSON 字符串。
 */
class JsonbTypeHandler : BaseTypeHandler<String?>() {

    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: String?, jdbcType: JdbcType?) {
        val obj = PGobject().apply {
            type = "jsonb"
            value = parameter
        }
        ps.setObject(i, obj)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): String? {
        val obj = rs.getObject(columnName) as? PGobject
        return obj?.value
    }

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): String? {
        val obj = rs.getObject(columnIndex) as? PGobject
        return obj?.value
    }

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): String? {
        val obj = cs.getObject(columnIndex) as? PGobject
        return obj?.value
    }
}
