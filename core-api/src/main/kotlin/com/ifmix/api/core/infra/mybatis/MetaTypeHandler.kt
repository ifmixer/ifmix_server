package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.entity.demo.Meta
import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import org.postgresql.util.PGobject
import tools.jackson.databind.json.JsonMapper
import java.sql.*

/**
 * Meta ↔ PG jsonb 类型处理器。
 * 使用 Jackson 3 序列化/反序列化。
 */
class MetaTypeHandler : BaseTypeHandler<Meta?>() {

    companion object {
        private val mapper = JsonMapper.builder().build()
    }

    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: Meta?, jdbcType: JdbcType?) {
        val obj = PGobject().apply {
            type = "jsonb"
            value = mapper.writeValueAsString(parameter)
        }
        ps.setObject(i, obj)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): Meta? =
        parse(rs.getObject(columnName))

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): Meta? =
        parse(rs.getObject(columnIndex))

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): Meta? =
        parse(cs.getObject(columnIndex))

    private fun parse(obj: Any?): Meta? {
        val json = when (obj) {
            is PGobject -> obj.value
            is String -> obj
            else -> return null
        }
        if (json.isNullOrBlank()) return null
        return mapper.readValue(json, Meta::class.java)
    }
}
