package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.modules.demo.mybatis.CoreTodoItemDynamicSqlSupport
import org.apache.ibatis.annotations.*
import org.apache.ibatis.type.JdbcType
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * TodoItem MyBatis Mapper 接口。
 */
@Mapper
interface TodoItemMapper {

    @Select("SELECT id, app_id, todo_id, content, done, created_at, updated_at, deleted_at " +
            "FROM core_todo_item WHERE id = #{id} AND deleted_at IS NULL LIMIT 1")
    @Results(id = "TodoItemResult", value = [
        Result(column = "id", property = "id", jdbcType = JdbcType.OTHER, id = true),
        Result(column = "app_id", property = "appId", jdbcType = JdbcType.OTHER),
        Result(column = "todo_id", property = "todoId", jdbcType = JdbcType.OTHER),
        Result(column = "content", property = "content", jdbcType = JdbcType.VARCHAR),
        Result(column = "done", property = "done", jdbcType = JdbcType.BOOLEAN),
        Result(column = "created_at", property = "createdAt", jdbcType = JdbcType.TIMESTAMP),
        Result(column = "updated_at", property = "updatedAt", jdbcType = JdbcType.TIMESTAMP),
        Result(column = "deleted_at", property = "deletedAt", jdbcType = JdbcType.TIMESTAMP),
    ])
    fun selectOne(@Param("id") id: UUID): Map<String, Any?>?

    @Select("SELECT id, app_id, todo_id, content, done, created_at, updated_at, deleted_at " +
            "FROM core_todo_item WHERE todo_id = #{todoId} AND deleted_at IS NULL ORDER BY created_at")
    @ResultMap("TodoItemResult")
    fun selectByTodoId(@Param("todoId") todoId: UUID): List<Map<String, Any?>>

    @Insert("INSERT INTO core_todo_item (id, app_id, todo_id, content, done, created_at, updated_at) " +
            "VALUES (#{id}, #{appId}, #{todoId}, #{content}, #{done}, #{createdAt}, #{updatedAt})")
    fun insert(record: Map<String, Any?>): Int

    @Update("UPDATE core_todo_item SET deleted_at = NOW() WHERE id = #{id} AND app_id = #{appId}")
    fun deleteById(@Param("id") id: UUID, @Param("appId") appId: UUID): Int
}
