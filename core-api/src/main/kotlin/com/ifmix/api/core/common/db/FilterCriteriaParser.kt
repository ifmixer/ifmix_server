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
