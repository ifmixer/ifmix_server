package com.ifmix.api.core.infra.jooq

import org.jooq.Condition
import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL
import java.time.Instant

/**
 * 通用动态查询条件解析器。
 *
 * 将 GraphQL FilterGroup input 解析为 jOOQ Condition。
 * 通过 fieldMap（Kotlin 属性名 → jOOQ TableField）+ allowedKeys 控制可查字段。
 *
 * 用法：
 * ```
 * val parser = FilterConditionParser(
 *     fieldMap = ScanRecord.FIELDS,
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
    fun parse(filterGroup: Map<String, Any?>?): Condition {
        if (filterGroup == null) return DSL.noCondition()
        return parseGroup(filterGroup, depth = 0)
    }

    private fun parseGroup(group: Map<String, Any?>, depth: Int): Condition {
        check(depth < MAX_DEPTH) { "Filter nesting too deep (max $MAX_DEPTH)" }

        @Suppress("UNCHECKED_CAST")
        val andList = group["and"] as? List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val orList = group["or"] as? List<Map<String, Any?>>

        return when {
            andList != null -> {
                andList.map { parseExpr(it, depth + 1) }
                    .fold(DSL.noCondition()) { acc, c -> acc.and(c) }
            }
            orList != null -> {
                val conditions = orList.map { parseExpr(it, depth + 1) }
                if (conditions.isEmpty()) DSL.noCondition()
                else conditions.reduce { acc, c -> acc.or(c) }
            }
            else -> DSL.noCondition()
        }
    }

    private fun parseExpr(expr: Map<String, Any?>, depth: Int): Condition {
        @Suppress("UNCHECKED_CAST")
        val fieldFilter = expr["field"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val groupFilter = expr["group"] as? Map<String, Any?>

        return when {
            fieldFilter != null -> parseFieldFilter(fieldFilter)
            groupFilter != null -> parseGroup(groupFilter, depth)
            else -> DSL.noCondition()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFieldFilter(filter: Map<String, Any?>): Condition {
        val fieldName = filter["field"] as? String
            ?: throw IllegalArgumentException("FieldFilter.field is required")
        val opStr = filter["op"] as? String
            ?: throw IllegalArgumentException("FieldFilter.op is required")

        require(fieldName in allowedKeys) { "Field '$fieldName' is not allowed for filtering" }

        val jooqField = fieldMap[fieldName]
            ?: throw IllegalArgumentException("Unknown field: $fieldName")

        val value = filter["value"]
        val values = filter["values"] as? List<*>

        return buildCondition(jooqField as Field<Any?>, opStr, value, values)
    }

    private fun buildCondition(field: Field<Any?>, op: String, value: Any?, values: List<*>?): Condition {
        return when (op.uppercase()) {
            "EQ" -> {
                requireNotNull(value) { "value required for EQ" }
                field.eq(coerce(field, value))
            }
            "NE" -> {
                requireNotNull(value) { "value required for NE" }
                field.ne(coerce(field, value))
            }
            "GT" -> {
                requireNotNull(value) { "value required for GT" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).gt(coerce(field, value) as Comparable<Any>)
            }
            "GTE" -> {
                requireNotNull(value) { "value required for GTE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).ge(coerce(field, value) as Comparable<Any>)
            }
            "LT" -> {
                requireNotNull(value) { "value required for LT" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).lt(coerce(field, value) as Comparable<Any>)
            }
            "LTE" -> {
                requireNotNull(value) { "value required for LTE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<Comparable<Any>>).le(coerce(field, value) as Comparable<Any>)
            }
            "LIKE" -> {
                requireNotNull(value) { "value required for LIKE" }
                @Suppress("UNCHECKED_CAST")
                (field as Field<String>).like(value.toString())
            }
            "IN" -> {
                requireNotNull(values) { "values required for IN" }
                if (values.isEmpty()) DSL.falseCondition()
                else field.`in`(values.map { coerce(field, it) })
            }
            "NIN" -> {
                requireNotNull(values) { "values required for NIN" }
                if (values.isEmpty()) DSL.noCondition()
                else field.notIn(values.map { coerce(field, it) })
            }
            "IS_NULL" -> field.isNull
            "IS_NOT_NULL" -> field.isNotNull
            else -> throw IllegalArgumentException("Unknown filter op: $op")
        }
    }

    /**
     * 将 JSON 值转换为 jOOQ 字段期望的类型。
     * GraphQL/Jackson 传进来的可能是 Int/Long/String/Boolean，需要适配。
     */
    private fun coerce(field: Field<*>, value: Any?): Any? {
        if (value == null) return null
        val fieldType = field.type
        return when {
            fieldType == java.util.UUID::class.java && value is String -> java.util.UUID.fromString(value)
            fieldType == Instant::class.java && value is String -> Instant.parse(value)
            fieldType == Instant::class.java && value is Number -> Instant.ofEpochMilli(value.toLong())
            fieldType == Int::class.java && value is Number -> value.toInt()
            fieldType == Long::class.java && value is Number -> value.toLong()
            fieldType == Boolean::class.java && value is Boolean -> value
            fieldType == String::class.java -> value.toString()
            Number::class.java.isAssignableFrom(fieldType) && value is Number -> value
            else -> value
        }
    }
}
