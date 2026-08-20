package com.ifmix.api.core.generated.mybatis.mapper

import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.content
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.createdAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.todoId
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.todoItem
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.model.TodoItem
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
import org.mybatis.dynamic.sql.util.kotlin.CountCompleter
import org.mybatis.dynamic.sql.util.kotlin.DeleteCompleter
import org.mybatis.dynamic.sql.util.kotlin.SelectCompleter
import org.mybatis.dynamic.sql.util.kotlin.UpdateCompleter
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.countFrom
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.deleteFrom
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insert
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insertMultiple
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectDistinct
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectList
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectOne
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.mybatis.dynamic.sql.util.mybatis3.CommonCountMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonDeleteMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonInsertMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonUpdateMapper

@Mapper
interface TodoItemMapper : CommonCountMapper, CommonDeleteMapper, CommonInsertMapper<TodoItem>, CommonUpdateMapper {
    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @Results(id="TodoItemResult")
    @Arg(column="id", jdbcType=JdbcType.OTHER, javaType=UUID::class, id=true)
    @Arg(column="todo_id", jdbcType=JdbcType.OTHER, javaType=UUID::class)
    @Arg(column="app_id", jdbcType=JdbcType.OTHER, javaType=UUID::class)
    @Arg(column="content", jdbcType=JdbcType.VARCHAR, javaType=String::class)
    @Arg(column="done", jdbcType=JdbcType.BIT, javaType=Boolean::class)
    @Arg(column="created_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    @Arg(column="updated_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    @Arg(column="deleted_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    fun selectMany(selectStatement: SelectStatementProvider): List<TodoItem>

    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @ResultMap("TodoItemResult")
    fun selectOne(selectStatement: SelectStatementProvider): TodoItem?
}

fun TodoItemMapper.count(completer: CountCompleter) =
    countFrom(this::count, todoItem, completer)

fun TodoItemMapper.delete(completer: DeleteCompleter) =
    deleteFrom(this::delete, todoItem, completer)

fun TodoItemMapper.deleteByPrimaryKey(id_: UUID) =
    delete {
        where { id isEqualTo id_ }
    }

fun TodoItemMapper.insert(row: TodoItem) =
    insert(this::insert, row, todoItem) {
        withMappedColumn(id)
        withMappedColumn(todoId)
        withMappedColumn(appId)
        withMappedColumn(content)
        withMappedColumn(done)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumn(deletedAt)
    }

fun TodoItemMapper.insertMultiple(records: Collection<TodoItem>) =
    insertMultiple(this::insertMultiple, records, todoItem) {
        withMappedColumn(id)
        withMappedColumn(todoId)
        withMappedColumn(appId)
        withMappedColumn(content)
        withMappedColumn(done)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumn(deletedAt)
    }

fun TodoItemMapper.insertMultiple(vararg records: TodoItem) =
    insertMultiple(records.toList())

fun TodoItemMapper.insertSelective(row: TodoItem) =
    insert(this::insert, row, todoItem) {
        withMappedColumn(id)
        withMappedColumn(todoId)
        withMappedColumn(appId)
        withMappedColumn(content)
        withMappedColumn(done)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumnWhenPresent(deletedAt, row::deletedAt)
    }

private val columnList = listOf(id, todoId, appId, content, done, createdAt, updatedAt, deletedAt)

fun TodoItemMapper.select(completer: SelectCompleter) =
    selectList(this::selectMany, columnList, todoItem, completer)

fun TodoItemMapper.selectOne(completer: SelectCompleter) =
    selectOne(this::selectOne, columnList, todoItem, completer)

fun TodoItemMapper.selectDistinct(completer: SelectCompleter) =
    selectDistinct(this::selectMany, columnList, todoItem, completer)

fun TodoItemMapper.selectByPrimaryKey(id_: UUID) =
    selectOne {
        where { id isEqualTo id_ }
    }

fun TodoItemMapper.update(completer: UpdateCompleter) =
    update(this::update, todoItem, completer)

fun TodoItemMapper.updateByPrimaryKey(row: TodoItem) =
    update {
        set(todoId) equalTo row::todoId
        set(appId) equalTo row::appId
        set(content) equalTo row::content
        set(done) equalTo row::done
        set(createdAt) equalTo row::createdAt
        set(updatedAt) equalTo row::updatedAt
        set(deletedAt) equalToOrNull row::deletedAt
        where { id isEqualTo row.id }
    }