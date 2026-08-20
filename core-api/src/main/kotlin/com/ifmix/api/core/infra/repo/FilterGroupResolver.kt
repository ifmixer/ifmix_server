package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.generated.types.FieldFilter
import com.ifmix.api.core.generated.types.FilterExpr
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.FilterOp
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.meta.TypedProp
import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import java.time.Instant
import java.util.UUID

/**
 * 将 FilterGroup DSL 解析为 Jimmer where 谓词。
 *
 * 前端传参约定：
 * - UUID 字段：传 String（如 "550e8400-e29b-41d4-a716-446655440000"）
 * - Instant 字段：传 epoch millis 整数（如 1692518400000）
 * - Boolean 字段：传 true/false
 * - String 字段：传 String
 * - Int 字段：传整数
 *
 * 后端根据 TypedProp 声明的 Java 类型自动转换。
 */
object FilterGroupResolver {

    fun <E : Any> apply(
        query: KMutableRootQuery.ForEntity<E>,
        filter: FilterGroup?,
        allowedProps: Collection<TypedProp.Scalar<E, *>>,
    ) {
        if (filter == null) return
        val propMap = allowedProps.associate { it.unwrap().name to it.unwrap() }
        val predicate = resolveGroup(query, filter, propMap)
        predicate?.let { query.where(it) }
    }

    private fun <E : Any> resolveGroup(
        query: KMutableRootQuery.ForEntity<E>,
        group: FilterGroup,
        allowed: Map<String, ImmutableProp>,
    ): KNonNullExpression<Boolean>? {
        if (!group.and.isNullOrEmpty()) {
            val predicates = group.and.mapNotNull { resolveExpr(query, it, allowed) }
            if (predicates.isEmpty()) return null
            return and(*predicates.toTypedArray())
        }
        if (!group.or.isNullOrEmpty()) {
            val predicates = group.or.mapNotNull { resolveExpr(query, it, allowed) }
            if (predicates.isEmpty()) return null
            return or(*predicates.toTypedArray())
        }
        return null
    }

    private fun <E : Any> resolveExpr(
        query: KMutableRootQuery.ForEntity<E>,
        expr: FilterExpr,
        allowed: Map<String, ImmutableProp>,
    ): KNonNullExpression<Boolean>? {
        expr.field?.let { return resolveFieldFilter(query, it, allowed) }
        expr.group?.let { return resolveGroup(query, it, allowed) }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E : Any> resolveFieldFilter(
        query: KMutableRootQuery.ForEntity<E>,
        filter: FieldFilter,
        allowed: Map<String, ImmutableProp>,
    ): KNonNullExpression<Boolean> {
        val fieldName = filter.field
        val prop = allowed[fieldName]
            ?: throw IllegalArgumentException("Field '$fieldName' is not allowed for filtering")

        val targetType = prop.returnClass
        val column = query.table.get<Comparable<Any>>(fieldName)

        return when (filter.op) {
            FilterOp.EQ -> column eq coerce(filter.value, targetType)
            FilterOp.NE -> column ne coerce(filter.value, targetType)
            FilterOp.GT -> column gt coerce(filter.value, targetType)
            FilterOp.GTE -> column ge coerce(filter.value, targetType)
            FilterOp.LT -> column lt coerce(filter.value, targetType)
            FilterOp.LTE -> column le coerce(filter.value, targetType)
            FilterOp.LIKE -> (column as KExpression<String>).like(filter.value as String, LikeMode.ANYWHERE)
            FilterOp.IN -> column valueIn coerceList(filter.values, targetType)
            FilterOp.NIN -> column valueNotIn coerceList(filter.values, targetType)
            FilterOp.IS_NULL -> column.isNull()
            FilterOp.IS_NOT_NULL -> column.isNotNull()
        }
    }

    // ===== 值类型转换 =====

    @Suppress("UNCHECKED_CAST")
    private fun coerce(value: Any?, targetType: Class<*>): Comparable<Any> {
        requireNotNull(value) { "Filter value must not be null for this operator" }
        return coerceSingle(value, targetType) as Comparable<Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerceList(values: List<Any>?, targetType: Class<*>): List<Comparable<Any>> {
        requireNotNull(values) { "Filter values must not be null for IN/NIN operator" }
        return values.map { coerceSingle(it, targetType) as Comparable<Any> }
    }

    /**
     * 根据目标类型转换前端传来的 JSON 值。
     *
     * 前端传参规则：
     * - UUID: 字符串 "550e8400-..."
     * - Instant: epoch millis 整数 (Long/Int)
     * - Boolean: true/false
     * - String: 字符串
     * - Number: 数字
     */
    private fun coerceSingle(value: Any, targetType: Class<*>): Any = when {
        targetType == UUID::class.java || targetType == java.util.UUID::class.java ->
            when (value) {
                is UUID -> value
                is String -> UUID.fromString(value)
                else -> throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to UUID")
            }

        targetType == Instant::class.java || targetType == java.time.Instant::class.java ->
            when (value) {
                is Instant -> value
                is Number -> Instant.ofEpochMilli(value.toLong())
                is String -> Instant.parse(value)
                else -> throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to Instant")
            }

        targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java ->
            when (value) {
                is Boolean -> value
                is String -> value.toBoolean()
                else -> throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to Boolean")
            }

        targetType == String::class.java ->
            value.toString()

        targetType == Int::class.java || targetType == java.lang.Integer::class.java ->
            when (value) {
                is Number -> value.toInt()
                is String -> value.toInt()
                else -> throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to Int")
            }

        targetType == Long::class.java || targetType == java.lang.Long::class.java ->
            when (value) {
                is Number -> value.toLong()
                is String -> value.toLong()
                else -> throw IllegalArgumentException("Cannot convert ${value::class.simpleName} to Long")
            }

        // fallback: 原样返回，让 Jimmer 自己处理
        else -> value
    }
}
