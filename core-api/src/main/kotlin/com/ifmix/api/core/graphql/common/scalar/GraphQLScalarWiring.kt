package com.ifmix.api.core.graphql.common.scalar

import graphql.schema.idl.RuntimeWiring
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer

/**
 * 注册自定义 GraphQL scalar。
 * DateTime 使用自定义 DateTimeScalar（覆盖 extended-scalars 的 OffsetDateTime 版本）。
 * JSON 使用 graphql-java-extended-scalars 的 JsonScalar。
 */
@Configuration
class GraphQLScalarWiring : RuntimeWiringConfigurer {

    override fun configure(wiring: RuntimeWiring.Builder) {
        // DateTime: 使用自定义 Coercing（映射 Instant ↔ ISO-8601 String）
        wiring.scalar(
            graphql.schema.GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("ISO-8601 date-time scalar")
                .coercing(DateTimeScalar())
                .build()
        )
        // JSON: 支持任意 JSON 对象
        @Suppress("UNCHECKED_CAST")
        wiring.scalar(graphql.scalars.`object`.JsonScalar.INSTANCE as graphql.schema.GraphQLScalarType)
    }
}
