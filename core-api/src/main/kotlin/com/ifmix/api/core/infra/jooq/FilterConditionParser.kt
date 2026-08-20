package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.generated.types.FieldFilter
import com.ifmix.api.core.generated.types.FilterExpr
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.FilterOp
import org.jooq.Condition
import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL
import java.time.Instant

/**
 * 通用动态查询条件解析器。
 *
 * 将 DGS codegen 生成的 FilterGroup 直接解析为 jOOQ Condition。
 * 通过 fieldMap（Kotlin 属性名 → jOOQ TableField）+ allowedKeys 控制可查字段。
 *
 * 用法：
 * ```
 * val parser = FilterConditionParser(
 *     fieldMap = ScanRecordRepository.FIELD_MAP,
 *     allowedKeys = setOf("status", "collected", "lang", "createdAt"),
 * )
 * val condition = parser.parse(filterGroup)
 * // → 拼接到 .where(baseCond.and(condition))
 * ```
 */
class FilterConditionParser(
    private val fieldMap: Map<String, TableField<*, *>>,
    private val allowedKeys: Set<String> = fieldMap.keys,
) {

    companion object {
        /** 最大嵌套深度，防止恶意深层嵌套 DoS */
        private const val MAX_DEPTH = 5
    }

    /**
     * 解析顶层 FilterGroup。null 输入返回 noCondition()。
     */
    fun parse(filter: FilterGroup?): Condition {
        if (filter == null) return DSL.noCondition()
        return parseGroup(filter, depth = 0)
    }

    private fun parseGroup(group: FilterGroup, depth: Int): Condition {
        check(depth < MAX_DEPTH) { "Filter nesting too deep (max $MAX_DEPTH)" }

        return when {
            group.and != null -> {
                group.and.map { parseExpr(it, depth + 1) }
                    .fold(DSL.noCondition()) { acc, c -> acc.and(c) }
            }
            group.or != null -> {
                val conditions = group.or.map { parseExpr(it, depth + 1) }
                if (conditions.isEmpty()) DSL.noCondition()
                else conditions.reduce { acc, c -> acc.or(c) }
            }
            else -> DSL.noCondition()
        }
    }

    private fun parseExpr(expr: FilterExpr, depth: Int): Condition {
        return when {
            expr.field != null -> parseFieldFilter(expr.field)
            expr.group != null -> parseGroup(expr.group, depth)
            else -> DSL.noCondition()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFieldFilter(filter: FieldFilter): Condition {
        val fieldName = filter.field
        require(fieldName in allowedKeys) { "Field '$fieldName' is not allowed for filtering" }

        val jooqField = fieldMap[fieldName]
            ?: throw IllegalArgumentException("Unknown field: $fieldName")

        return buildCondition(jooqField as Field<Any?>, filter.op, filter.value, filter.values)
    }

    private fun buildCondition(field: Field<Any?>, op: FilterOp, value: Any?, values: List<Any>?): Condition {
        return when (op) {
            FilterOp.EQ -> {
                requireNotNull(value) { "value required for EQ" }
                field.eq(coerce(field, value))
            }
            FilterOp.NE -> {
                requireNotNull(value) { "value required for NE" }
                field.ne(coerce(field, value))
            }
            FilterOp.GT -> {
                requireNotNull(value) { "value required for GT" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).gt(coerce(field, value) as Comparable<Any>)
            }
            FilterOp.GTE -> {
                requireNotNull(value) { "value required for GTE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).ge(coerce(field, value) as Comparable<Any>)
            }
            FilterOp.LT -> {
                requireNotNull(value) { "value required for LT" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).lt(coerce(field, value) as Comparable<Any>)
            }
            FilterOp.LTE -> {
                requireNotNull(value) { "value required for LTE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).le(coerce(field, value) as Comparable<Any>)
            }
            FilterOp.LIKE -> {
                requireNotNull(value) { "value required for LIKE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<String>).like(value.toString())
            }
            FilterOp.IN -> {
                requireNotNull(values) { "values required for IN" }
                if (values.isEmpty()) DSL.falseCondition()
                else field.`in`(values.map { coerce(field, it) })
            }
            FilterOp.NIN -> {
                requireNotNull(values) { "values required for NIN" }
                if (values.isEmpty()) DSL.noCondition()
                else field.notIn(values.map { coerce(field, it) })
            }
            FilterOp.IS_NULL -> field.isNull
            FilterOp.IS_NOT_NULL -> field.isNotNull
        }
    }

    /**
     * 将 GraphQL 传入的值转换为 jOOQ 字段期望的类型。
     * Jackson 反序列化 Any 时：整数→Int、大整数→Long、小数→Double、字符串→String。
     */
    private fun coerce(field: Field<*>, value: Any?): Any? {
        if (value == null) return null
        val fieldType = field.type
        return when {
            fieldType == java.util.UUID::class.java && value is String -> java.util.UUID.fromString(value)
            fieldType == Instant::class.java && value is String -> Instant.parse(value)
            fieldType == Instant::class.java && value is Number -> Instant.ofEpochMilli(value.toLong())
            fieldType == Int::class.javaObjectType && value is Number -> value.toInt()
            fieldType == Long::class.javaObjectType && value is Number -> value.toLong()
            fieldType == Boolean::class.javaObjectType && value is Boolean -> value
            fieldType == String::class.java -> value.toString()
            Number::class.java.isAssignableFrom(fieldType) && value is Number -> value.toInt()
            else -> value
        }
    }
}
