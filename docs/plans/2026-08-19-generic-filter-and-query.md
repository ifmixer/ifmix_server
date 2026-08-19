# 通用动态查询 Filter

> 日期: 2026-08-19
> 依赖: 架构重组计划 (2026-08-19-architecture-restructure.md)
> 参考: ifmix_server FilterConditionParser + filter.graphqls

## 目标

实现 MongoDB 版通用 Filter DSL + 游标分页，支持 GraphQL 客户端动态组合查询条件（AND/OR 嵌套 + 多操作符），后端通过 KProperty 白名单控制安全边界，零手写类型声明。

## GraphQL Schema

加入 `schema/common.graphqls`：

```graphql
# ===== 通用动态查询 Filter DSL =====

enum FilterOp {
  EQ
  NE
  GT
  GTE
  LT
  LTE
  IN
  NIN
  LIKE
  IS_NULL
  IS_NOT_NULL
}

"单个字段条件"
input FieldFilter {
  "字段名（需在后端白名单中）"
  field: String!
  "操作符"
  op: FilterOp!
  "标量值（EQ/NE/GT/GTE/LT/LTE/LIKE 使用）"
  value: JSON
  "数组值（IN/NIN 使用）"
  values: [JSON!]
}

"组合条件（支持嵌套）"
input FilterGroup {
  "所有条件取 AND（与 or 二选一，同时填写则 AND 优先）"
  and: [FilterExpr!]
  "所有条件取 OR"
  or: [FilterExpr!]
}

"条件表达式 = 字段条件 | 嵌套组合（二选一）"
input FilterExpr {
  "字段级条件"
  field: FieldFilter
  "嵌套组合"
  group: FilterGroup
}
```

各 list query 统一加 `filter: FilterGroup` 可选参数：

```graphql
type Query {
    query_demo_listTodos(cursor: String, limit: Int, filter: FilterGroup): TodoConnection!
    query_ai_listScans(cursor: String, limit: Int, filter: FilterGroup): ScanConnection!
    query_ai_listCollectionItems(collectionId: ObjectId, cursor: String, limit: Int, filter: FilterGroup): CollectionItemConnection!
}
```

## 实现

### DGS Codegen 生成的类型

Filter schema 由 DGS codegen 自动生成强类型 Kotlin class（不手写）：

```kotlin
// 自动生成
data class FilterGroup(val and: List<FilterExpr>?, val or: List<FilterExpr>?)
data class FilterExpr(val field: FieldFilter?, val group: FilterGroup?)
data class FieldFilter(val field: String, val op: FilterOp, val value: Any?, val values: List<Any>?)
enum class FilterOp { EQ, NE, GT, GTE, LT, LTE, IN, NIN, LIKE, IS_NULL, IS_NOT_NULL }
```

### FilterCriteriaParser

放置：`common/db/FilterCriteriaParser.kt`

白名单用 `KProperty1` 声明——编译期检查属性存在性，自动推断类型，属性改名时编译报错。

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.graphql.generated.types.FieldFilter
import com.ifmix.api.core.graphql.generated.types.FilterExpr
import com.ifmix.api.core.graphql.generated.types.FilterGroup
import com.ifmix.api.core.graphql.generated.types.FilterOp
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.query.Criteria
import java.time.Instant
import kotlin.reflect.KProperty1

/**
 * 通用动态查询条件解析器 (MongoDB)。
 *
 * 白名单通过 KProperty1 声明，自动从属性 returnType 推断 coerce 类型。
 *
 * 用法：
 * ```
 * val parser = FilterCriteriaParser(setOf(
 *     ScanRecordEntity::status,
 *     ScanRecordEntity::collected,
 *     ScanRecordEntity::createdAt,
 * ))
 * val criteria = parser.parse(filterGroup)
 * ```
 */
class FilterCriteriaParser(
    allowedFields: Set<KProperty1<*, *>>,
) {
    companion object {
        private const val MAX_DEPTH = 5
    }

    private val fieldMap: Map<String, KProperty1<*, *>> = allowedFields.associateBy { it.name }

    fun parse(filter: FilterGroup?): Criteria {
        if (filter == null) return Criteria()
        return parseGroup(filter, depth = 0)
    }

    private fun parseGroup(group: FilterGroup, depth: Int): Criteria {
        check(depth < MAX_DEPTH) { "Filter nesting too deep (max $MAX_DEPTH)" }
        return when {
            group.and != null -> {
                val conditions = group.and.map { parseExpr(it, depth + 1) }
                if (conditions.isEmpty()) Criteria()
                else Criteria().andOperator(*conditions.toTypedArray())
            }
            group.or != null -> {
                val conditions = group.or.map { parseExpr(it, depth + 1) }
                if (conditions.isEmpty()) Criteria()
                else Criteria().orOperator(*conditions.toTypedArray())
            }
            else -> Criteria()
        }
    }

    private fun parseExpr(expr: FilterExpr, depth: Int): Criteria = when {
        expr.field != null -> parseFieldFilter(expr.field)
        expr.group != null -> parseGroup(expr.group, depth)
        else -> Criteria()
    }

    private fun parseFieldFilter(filter: FieldFilter): Criteria {
        val prop = fieldMap[filter.field]
            ?: throw IllegalArgumentException("Field '${filter.field}' is not allowed for filtering")

        val mongoField = if (filter.field == "id") "_id" else filter.field
        val value = filter.value?.let { coerce(it, prop) }
        val values = filter.values?.map { coerce(it, prop) }

        return buildCriteria(mongoField, filter.op, value, values)
    }

    private fun buildCriteria(field: String, op: FilterOp, value: Any?, values: List<Any?>?): Criteria {
        val c = Criteria.where(field)
        return when (op) {
            FilterOp.EQ -> c.`is`(value)
            FilterOp.NE -> c.ne(value)
            FilterOp.GT -> c.gt(value!!)
            FilterOp.GTE -> c.gte(value!!)
            FilterOp.LT -> c.lt(value!!)
            FilterOp.LTE -> c.lte(value!!)
            FilterOp.LIKE -> c.regex(Regex.escape(value.toString()), "i")
            FilterOp.IN -> c.`in`(values!!)
            FilterOp.NIN -> c.nin(values!!)
            FilterOp.IS_NULL -> c.`is`(null)
            FilterOp.IS_NOT_NULL -> c.ne(null)
        }
    }

    private fun coerce(value: Any?, prop: KProperty1<*, *>): Any? {
        if (value == null) return null
        return when (prop.returnType.classifier) {
            String::class -> value.toString()
            Boolean::class -> value as Boolean
            Int::class -> (value as Number).toInt()
            Long::class -> (value as Number).toLong()
            Instant::class -> when (value) {
                is Number -> Instant.ofEpochMilli(value.toLong())
                is String -> Instant.parse(value)
                else -> value
            }
            ObjectId::class -> ObjectId(value.toString())
            else -> value
        }
    }
}
```

### CRUDOps 集成

`findByCursor` 加可选 `filterCriteria` 参数：

```kotlin
fun findByCursor(
    ctx: RepoCtx,
    appId: ObjectId,
    input: CursorQueryInput,
    filterCriteria: Criteria = Criteria(),
): Page<T> {
    val query = Query()
    if (appScoped) query.addCriteria(Criteria.where("appId").`is`(appId))
    if (softDeletable) query.addCriteria(Criteria.where("deletedAt").`is`(null))
    // 动态 filter
    if (filterCriteria.criteriaObject.isNotEmpty()) {
        query.addCriteria(filterCriteria)
    }
    // cursor keyset + sort + limit ...
    // ...
}
```

### Repo 使用

每个 Repo 声明自己的 filterParser（KProperty 白名单）：

```kotlin
@Component
class ScanRecordRepository(mongo: MongoTemplate) {
    private val ops = CRUDOps(mongo, ScanRecordEntity::class.java)

    private val filterParser = FilterCriteriaParser(setOf(
        ScanRecordEntity::status,
        ScanRecordEntity::collected,
        ScanRecordEntity::tier,
        ScanRecordEntity::userId,
        ScanRecordEntity::createdAt,
    ))

    fun findByCursor(
        ctx: RepoCtx,
        appId: ObjectId,
        input: CursorQueryInput,
        filter: FilterGroup? = null,
    ): Page<ScanRecordEntity> =
        ops.findByCursor(ctx, appId, input, filterParser.parse(filter))
}

@Component
class TodoRepository(mongo: MongoTemplate) {
    private val ops = CRUDOps(mongo, TodoEntity::class.java)

    private val filterParser = FilterCriteriaParser(setOf(
        TodoEntity::title,
        TodoEntity::done,
        TodoEntity::userId,
        TodoEntity::createdAt,
    ))

    fun findByCursor(
        ctx: RepoCtx,
        appId: ObjectId,
        input: CursorQueryInput,
        filter: FilterGroup? = null,
    ): Page<TodoEntity> =
        ops.findByCursor(ctx, appId, input, filterParser.parse(filter))
}
```

### 全链路强类型透传

```kotlin
// DataFetcher
@DgsQuery(field = "query_ai_listScans")
fun listScans(
    @InputArgument cursor: String?,
    @InputArgument limit: Int?,
    @InputArgument filter: FilterGroup?,
    dfe: DgsDataFetchingEnvironment,
): Page<ScanRecordEntity> {
    val opCtx = getOpCtx(dfe)
    return aiFacade.listScans(opCtx, CursorQueryInput(cursor, limit), filter)
}

// Facade
fun listScans(opCtx: OperationContext, input: CursorQueryInput, filter: FilterGroup?): Page<ScanRecordEntity> =
    scanHandler.listScans(ModuleCtx.from(opCtx), input, filter)

// Handler
fun listScans(mc: ModuleCtx, input: CursorQueryInput, filter: FilterGroup?): Page<ScanRecordEntity> =
    repo.findByCursor(RepoCtx.from(mc), mc.appId, input, filter)
```

## 游标分页 (CursorQueryInput)

游标分页与 filter 配合使用，统一由 `CRUDOps.findByCursor` 处理：

```kotlin
data class CursorQueryInput(
    val cursor: String? = null,
    val limit: Int? = null,
    val sortBy: String = "id",
    val order: Order = Order.DESC,
) {
    enum class Order { ASC, DESC }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }

    fun effectiveLimit() = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
```

游标编码（自包含，base64url）：
- sortBy 为 `id` → cursor 直接是 ObjectId hex
- sortBy 为其他字段 → cursor 编码为 `{type}|{sortValue}|{idHex}` 的 base64url

## 安全设计

| 防护 | 实现 |
|------|------|
| 字段注入 | KProperty 白名单，非白名单字段运行时拒绝 + 编译期保证属性存在 |
| 深度攻击 | `MAX_DEPTH = 5`，超限抛异常 |
| 宽度攻击 | 可选：限制单个 FilterGroup 中 and/or 数组长度（如 20） |
| 类型安全 | `coerce()` 从 KProperty.returnType 自动推断，类型不匹配抛异常 |
| id → _id | 自动映射 |
| 属性重命名 | 白名单使用 KProperty 引用，编译报错 |

## 客户端用法示例

简单过滤：
```graphql
query {
  query_ai_listScans(
    limit: 20
    filter: {
      and: [
        { field: { field: "status", op: EQ, value: "COMPLETED" } }
        { field: { field: "collected", op: EQ, value: true } }
      ]
    }
  ) {
    items { id status collected createdAt }
    nextCursor
    hasMore
  }
}
```

时间范围 + OR：
```graphql
query {
  query_demo_listTodos(
    filter: {
      and: [
        { field: { field: "createdAt", op: GTE, value: "2026-08-01T00:00:00Z" } }
        { group: {
          or: [
            { field: { field: "done", op: EQ, value: true } }
            { field: { field: "title", op: LIKE, value: "重要" } }
          ]
        }}
      ]
    }
  ) {
    items { id title done createdAt }
    nextCursor
    hasMore
  }
}
```

IN 操作：
```graphql
query {
  query_ai_listScans(
    filter: {
      and: [
        { field: { field: "tier", op: IN, values: ["PRO", "ENTERPRISE"] } }
      ]
    }
  ) { items { id tier } hasMore }
}
```

## 实施步骤

1. `common.graphqls` 加入 filter types（FilterOp, FieldFilter, FilterGroup, FilterExpr）
2. 创建 `common/db/FilterCriteriaParser.kt`
3. `CRUDOps.findByCursor` 加 `filterCriteria: Criteria` 参数
4. 各 Repo 声明 `filterParser`（KProperty 白名单）
5. 各 list query schema 加 `filter: FilterGroup` 参数
6. DataFetcher → Facade → Handler → Repo 透传 `FilterGroup?`
7. 测试：各操作符 + AND/OR 组合 + 深度限制 + 非法字段拒绝 + 游标分页 + filter 联合
