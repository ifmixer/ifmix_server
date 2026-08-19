package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.modules.demo.mybatis.CoreTodoDynamicSqlSupport
import org.apache.ibatis.annotations.*
import org.apache.ibatis.type.JdbcType
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Todo MyBatis Mapper 接口 — 使用参数化 SQL。
 */
@Mapper
interface TodoMapper {

    @Select("SELECT id, app_id, install_id, user_id, title, done, created_at, updated_at, deleted_at " +
            "FROM core_todo WHERE app_id = #{appId} AND id = #{id} AND deleted_at IS NULL LIMIT 1")
    @Results(id = "TodoResult", value = [
        Result(column = "id", property = "id", jdbcType = JdbcType.OTHER, id = true),
        Result(column = "app_id", property = "appId", jdbcType = JdbcType.OTHER),
        Result(column = "install_id", property = "installId", jdbcType = JdbcType.OTHER),
        Result(column = "user_id", property = "userId", jdbcType = JdbcType.OTHER),
        Result(column = "title", property = "title", jdbcType = JdbcType.VARCHAR),
        Result(column = "done", property = "done", jdbcType = JdbcType.BOOLEAN),
        Result(column = "created_at", property = "createdAt", jdbcType = JdbcType.TIMESTAMP),
        Result(column = "updated_at", property = "updatedAt", jdbcType = JdbcType.TIMESTAMP),
        Result(column = "deleted_at", property = "deletedAt", jdbcType = JdbcType.TIMESTAMP),
    ])
    fun selectOneByAppAndId(@Param("appId") appId: UUID, @Param("id") id: UUID): Map<String, Any?>?

    @Select("SELECT id, app_id, install_id, user_id, title, done, created_at, updated_at, deleted_at " +
            "FROM core_todo WHERE app_id = #{appId} AND deleted_at IS NULL" +
            " AND (#{cursor} IS NULL OR id < #{cursor})" +
            " ORDER BY id DESC LIMIT #{limit}")
    @ResultMap("TodoResult")
    fun selectByCursor(@Param("appId") appId: UUID, @Param("cursor") cursor: UUID?, @Param("limit") limit: Int): List<Map<String, Any?>>

    @Insert("INSERT INTO core_todo (id, app_id, install_id, user_id, title, done, created_at, updated_at) " +
            "VALUES (#{id}, #{appId}, #{installId}, #{userId}, #{title}, #{done}, #{createdAt}, #{updatedAt})")
    fun insert(record: Map<String, Any?>): Int

    @Update("UPDATE core_todo SET deleted_at = NOW() WHERE id = #{id} AND app_id = #{appId}")
    fun deleteById(@Param("id") id: UUID, @Param("appId") appId: UUID): Int

    @Select("SELECT COUNT(*) > 0 FROM core_todo WHERE app_id = #{appId} AND id = #{id} AND deleted_at IS NULL")
    fun exists(@Param("appId") appId: UUID, @Param("id") id: UUID): Boolean

    @Select("SELECT id, app_id, install_id, user_id, title, done, created_at, updated_at, deleted_at " +
            "FROM core_todo WHERE app_id = #{appId} AND id IN <ids> AND deleted_at IS NULL")
    @ResultMap("TodoResult")
    fun selectByIds(@Param("appId") appId: UUID, @Param("ids") ids: List<UUID>): List<Map<String, Any?>>
}
