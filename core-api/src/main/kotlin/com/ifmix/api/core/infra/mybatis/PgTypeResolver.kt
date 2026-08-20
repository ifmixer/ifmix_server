package com.ifmix.api.core.infra.mybatis

import org.mybatis.generator.api.IntrospectedColumn
import org.mybatis.generator.api.JavaTypeResolver.JdbcTypeInformation
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType
import org.mybatis.generator.internal.types.JavaTypeResolverDefaultImpl
import java.util.Optional

/**
 * 自定义 MBG JavaTypeResolver — 全局处理 PG 特殊类型。
 *
 * uuid → UUID, timestamptz → Instant, jsonb → String（默认，特定列通过 columnOverride 指定领域类型）
 */
class PgTypeResolver : JavaTypeResolverDefaultImpl() {

    override fun calculateTypeInformation(column: IntrospectedColumn): Optional<JdbcTypeInformation> {
        val pgType = column.actualTypeName?.lowercase() ?: ""

        val mapped: FullyQualifiedJavaType? = when (pgType) {
            "uuid" -> FullyQualifiedJavaType("java.util.UUID")
            "timestamptz", "timestamp", "timestamp with time zone", "timestamp without time zone" ->
                FullyQualifiedJavaType("java.time.Instant")
            "jsonb", "json" -> FullyQualifiedJavaType("java.lang.String")
            else -> null
        }

        if (mapped != null) {
            return Optional.of(JdbcTypeInformation("OTHER", mapped))
        }
        return super.calculateTypeInformation(column)
    }
}
