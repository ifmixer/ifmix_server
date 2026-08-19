package com.ifmix.api.core.infra.mybatis

import org.mybatis.generator.api.IntrospectedColumn
import org.mybatis.generator.api.JavaTypeResolver.JdbcTypeInformation
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType
import org.mybatis.generator.internal.types.JavaTypeResolverDefaultImpl
import java.util.Optional

/**
 * 自定义 MBG JavaTypeResolver — 全局处理 PG 特殊类型。
 *
 * 用法：generatorConfig.xml 中
 * <javaTypeResolver type="com.ifmix.api.core.infra.mybatis.PgTypeResolver"/>
 */
class PgTypeResolver : JavaTypeResolverDefaultImpl() {

    override fun calculateTypeInformation(column: IntrospectedColumn): Optional<JdbcTypeInformation> {
        // IntrospectedColumn.actualTypeName 是 PG JDBC 报的原始类型名
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
