package com.ifmix.api.core.modules.demo.mybatis.mapper

import com.ifmix.api.core.modules.demo.mybatis.model.CoreTodoItem
import java.time.Instant
import java.util.UUID
import org.apache.ibatis.annotations.Arg
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.ResultMap
import org.apache.ibatis.annotations.Results
import org.apache.ibatis.annotations.SelectProvider
import org.apache.ibatis.type.JdbcType
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider
import org.mybatis.dynamic.sql.util.SqlProviderAdapter
import org.mybatis.dynamic.sql.util.mybatis3.CommonCountMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonDeleteMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonInsertMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonUpdateMapper

/**
 * MyBatis Mapper 接口 — 定义结果映射。
 * 业务查询逻辑在 TodoItemRepository 中。
 */
@Mapper
interface CoreTodoItemMapper : CommonCountMapper, CommonDeleteMapper, CommonInsertMapper<CoreTodoItem>, CommonUpdateMapper {
    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @Results(id = "CoreTodoItemResult")
    @Arg(column = "id", jdbcType = JdbcType.OTHER, javaType = UUID::class, id = true)
    @Arg(column = "todo_id", jdbcType = JdbcType.OTHER, javaType = UUID::class)
    @Arg(column = "app_id", jdbcType = JdbcType.OTHER, javaType = UUID::class)
    @Arg(column = "content", jdbcType = JdbcType.VARCHAR, javaType = String::class)
    @Arg(column = "done", jdbcType = JdbcType.BIT, javaType = Boolean::class)
    @Arg(column = "created_at", jdbcType = JdbcType.OTHER, javaType = Instant::class)
    @Arg(column = "updated_at", jdbcType = JdbcType.OTHER, javaType = Instant::class)
    @Arg(column = "deleted_at", jdbcType = JdbcType.OTHER, javaType = Instant::class)
    fun selectMany(selectStatement: SelectStatementProvider): List<CoreTodoItem>

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @ResultMap("CoreTodoItemResult")
    fun selectOne(selectStatement: SelectStatementProvider): CoreTodoItem?
}
