package com.ifmix.api.core.generated.mybatis.mapper

import com.ifmix.api.core.entity.demo.Meta
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.coreTodo
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.createdAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.installId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.meta
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.note
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.title
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.userId
import com.ifmix.api.core.generated.mybatis.model.CoreTodo
import com.ifmix.api.core.infra.mybatis.MetaTypeHandler
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
interface CoreTodoMapper : CommonCountMapper, CommonDeleteMapper, CommonInsertMapper<CoreTodo>, CommonUpdateMapper {
    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @Results(id="CoreTodoResult")
    @Arg(column="id", jdbcType=JdbcType.OTHER, javaType=UUID::class, id=true)
    @Arg(column="title", jdbcType=JdbcType.VARCHAR, javaType=String::class)
    @Arg(column="done", jdbcType=JdbcType.BIT, javaType=Boolean::class)
    @Arg(column="app_id", jdbcType=JdbcType.OTHER, javaType=UUID::class)
    @Arg(column="created_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    @Arg(column="updated_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    @Arg(column="deleted_at", jdbcType=JdbcType.OTHER, javaType=Instant::class)
    @Arg(column="install_id", jdbcType=JdbcType.OTHER, javaType=UUID::class)
    @Arg(column="user_id", jdbcType=JdbcType.OTHER, javaType=UUID::class)
    @Arg(column="meta", typeHandler=MetaTypeHandler::class, jdbcType=JdbcType.OTHER, javaType=Meta::class)
    @Arg(column="note", jdbcType=JdbcType.VARCHAR, javaType=String::class)
    fun selectMany(selectStatement: SelectStatementProvider): List<CoreTodo>

    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @ResultMap("CoreTodoResult")
    fun selectOne(selectStatement: SelectStatementProvider): CoreTodo?
}

fun CoreTodoMapper.count(completer: CountCompleter) =
    countFrom(this::count, coreTodo, completer)

fun CoreTodoMapper.delete(completer: DeleteCompleter) =
    deleteFrom(this::delete, coreTodo, completer)

fun CoreTodoMapper.deleteByPrimaryKey(id_: UUID) =
    delete {
        where { id isEqualTo id_ }
    }

fun CoreTodoMapper.insert(row: CoreTodo) =
    insert(this::insert, row, coreTodo) {
        withMappedColumn(id)
        withMappedColumn(title)
        withMappedColumn(done)
        withMappedColumn(appId)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumn(deletedAt)
        withMappedColumn(installId)
        withMappedColumn(userId)
        withMappedColumn(meta)
        withMappedColumn(note)
    }

fun CoreTodoMapper.insertMultiple(records: Collection<CoreTodo>) =
    insertMultiple(this::insertMultiple, records, coreTodo) {
        withMappedColumn(id)
        withMappedColumn(title)
        withMappedColumn(done)
        withMappedColumn(appId)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumn(deletedAt)
        withMappedColumn(installId)
        withMappedColumn(userId)
        withMappedColumn(meta)
        withMappedColumn(note)
    }

fun CoreTodoMapper.insertMultiple(vararg records: CoreTodo) =
    insertMultiple(records.toList())

fun CoreTodoMapper.insertSelective(row: CoreTodo) =
    insert(this::insert, row, coreTodo) {
        withMappedColumn(id)
        withMappedColumn(title)
        withMappedColumn(done)
        withMappedColumn(appId)
        withMappedColumn(createdAt)
        withMappedColumn(updatedAt)
        withMappedColumnWhenPresent(deletedAt, row::deletedAt)
        withMappedColumnWhenPresent(installId, row::installId)
        withMappedColumnWhenPresent(userId, row::userId)
        withMappedColumnWhenPresent(meta, row::meta)
        withMappedColumnWhenPresent(note, row::note)
    }

private val columnList = listOf(id, title, done, appId, createdAt, updatedAt, deletedAt, installId, userId, meta, note)

fun CoreTodoMapper.select(completer: SelectCompleter) =
    selectList(this::selectMany, columnList, coreTodo, completer)

fun CoreTodoMapper.selectOne(completer: SelectCompleter) =
    selectOne(this::selectOne, columnList, coreTodo, completer)

fun CoreTodoMapper.selectDistinct(completer: SelectCompleter) =
    selectDistinct(this::selectMany, columnList, coreTodo, completer)

fun CoreTodoMapper.selectByPrimaryKey(id_: UUID) =
    selectOne {
        where { id isEqualTo id_ }
    }

fun CoreTodoMapper.update(completer: UpdateCompleter) =
    update(this::update, coreTodo, completer)

fun CoreTodoMapper.updateByPrimaryKey(row: CoreTodo) =
    update {
        set(title) equalTo row::title
        set(done) equalTo row::done
        set(appId) equalTo row::appId
        set(createdAt) equalTo row::createdAt
        set(updatedAt) equalTo row::updatedAt
        set(deletedAt) equalToOrNull row::deletedAt
        set(installId) equalToOrNull row::installId
        set(userId) equalToOrNull row::userId
        set(meta) equalToOrNull row::meta
        set(note) equalToOrNull row::note
        where { id isEqualTo row.id }
    }