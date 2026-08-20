package com.ifmix.api.core.modules.demo.repo.mybatis

import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import org.apache.ibatis.annotations.*
import org.mybatis.dynamic.sql.delete.render.DeleteStatementProvider
import org.mybatis.dynamic.sql.insert.render.InsertStatementProvider
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider
import org.mybatis.dynamic.sql.update.render.UpdateStatementProvider
import org.mybatis.dynamic.sql.util.SqlProviderAdapter

@Mapper
interface TodoMapper {

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @Results(id = "TodoRecordResult", value = [
        Result(column = "id", property = "id"),
        Result(column = "app_id", property = "appId"),
        Result(column = "title", property = "title"),
        Result(column = "done", property = "done"),
        Result(column = "install_id", property = "installId"),
        Result(column = "user_id", property = "userId"),
        Result(column = "note", property = "note"),
        Result(column = "meta", property = "meta"),
        Result(column = "created_at", property = "createdAt"),
        Result(column = "updated_at", property = "updatedAt"),
        Result(column = "deleted_at", property = "deletedAt"),
    ])
    fun selectMany(statement: SelectStatementProvider): List<TodoEntity>

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @ResultMap("TodoRecordResult")
    fun selectOne(statement: SelectStatementProvider): TodoEntity?

    @InsertProvider(type = SqlProviderAdapter::class, method = "insert")
    fun insert(statement: InsertStatementProvider<TodoEntity>): Int

    @UpdateProvider(type = SqlProviderAdapter::class, method = "update")
    fun update(statement: UpdateStatementProvider): Int

    @DeleteProvider(type = SqlProviderAdapter::class, method = "delete")
    fun delete(statement: DeleteStatementProvider): Int
}

@Mapper
interface TodoItemMapper {

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @Results(id = "TodoItemRecordResult", value = [
        Result(column = "id", property = "id"),
        Result(column = "app_id", property = "appId"),
        Result(column = "todo_id", property = "todoId"),
        Result(column = "content", property = "content"),
        Result(column = "done", property = "done"),
        Result(column = "created_at", property = "createdAt"),
        Result(column = "updated_at", property = "updatedAt"),
        Result(column = "deleted_at", property = "deletedAt"),
    ])
    fun selectMany(statement: SelectStatementProvider): List<TodoItemEntity>

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @ResultMap("TodoItemRecordResult")
    fun selectOne(statement: SelectStatementProvider): TodoItemEntity?

    @InsertProvider(type = SqlProviderAdapter::class, method = "insert")
    fun insert(statement: InsertStatementProvider<TodoItemEntity>): Int

    @UpdateProvider(type = SqlProviderAdapter::class, method = "update")
    fun update(statement: UpdateStatementProvider): Int

    @DeleteProvider(type = SqlProviderAdapter::class, method = "delete")
    fun delete(statement: DeleteStatementProvider): Int
}
