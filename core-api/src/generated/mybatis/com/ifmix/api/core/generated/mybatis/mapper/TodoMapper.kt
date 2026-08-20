package com.ifmix.api.core.generated.mybatis.mapper

import com.ifmix.api.core.entity.demo.Meta
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.createdAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.installId
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.meta
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.note
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.title
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.todo
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.userId
import com.ifmix.api.core.generated.mybatis.model.Todo
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
interface TodoMapper : CommonCountMapper, CommonDeleteMapper, CommonInsertMapper<Todo>, CommonUpdateMapper {
    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @Results(id="TodoResult")
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
    fun selectMany(selectStatement: SelectStatementProvider): List<Todo>

    @SelectProvider(type=SqlProviderAdapter::class, method="select")
    @ResultMap("TodoResult")
    fun selectOne(selectStatement: SelectStatementProvider): Todo?
}

fun TodoMapper.count(completer: CountCompleter) =
    countFrom(this::count, todo, completer)

fun TodoMapper.delete(completer: DeleteCompleter) =
    deleteFrom(this::delete, todo, completer)

fun TodoMapper.deleteByPrimaryKey(id_: UUID) =
    delete {
        where { id isEqualTo id_ }
    }

fun TodoMapper.insert(row: Todo) =
    insert(this::insert, row, todo) {
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

fun TodoMapper.insertMultiple(records: Collection<Todo>) =
    insertMultiple(this::insertMultiple, records, todo) {
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

fun TodoMapper.insertMultiple(vararg records: Todo) =
    insertMultiple(records.toList())

fun TodoMapper.insertSelective(row: Todo) =
    insert(this::insert, row, todo) {
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

fun TodoMapper.select(completer: SelectCompleter) =
    selectList(this::selectMany, columnList, todo, completer)

fun TodoMapper.selectOne(completer: SelectCompleter) =
    selectOne(this::selectOne, columnList, todo, completer)

fun TodoMapper.selectDistinct(completer: SelectCompleter) =
    selectDistinct(this::selectMany, columnList, todo, completer)

fun TodoMapper.selectByPrimaryKey(id_: UUID) =
    selectOne {
        where { id isEqualTo id_ }
    }

fun TodoMapper.update(completer: UpdateCompleter) =
    update(this::update, todo, completer)

fun TodoMapper.updateByPrimaryKey(row: Todo) =
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