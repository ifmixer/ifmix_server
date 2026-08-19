package com.ifmix.api.core.graphql.common.scalar

import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer

/**
 * 注册自定义 GraphQL scalar。
 * DateTime 使用自定义 DateTimeScalar（覆盖 extended-scalars 的 OffsetDateTime 版本）。
 * JSON 使用 graphql-java-extended-scalars 的 JsonScalar。
 * ObjectId 使用自定义 ObjectIdScalar，映射 24 位 hex ↔ org.bson.types.ObjectId。
 */
@Configuration
class GraphQLScalarWiring : RuntimeWiringConfigurer {

    override fun configure(wiring: RuntimeWiring.Builder) {
        // DateTime: 使用自定义 Coercing（映射 Instant ↔ ISO-8601 String）
        wiring.scalar(
            GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("ISO-8601 date-time scalar")
                .coercing(DateTimeScalar())
                .build()
        )
        // JSON: 支持任意 JSON 对象
        @Suppress("UNCHECKED_CAST")
        wiring.scalar(graphql.scalars.`object`.JsonScalar.INSTANCE as GraphQLScalarType)
        // ObjectId: 映射 ObjectId ↔ 24-char hex string
        wiring.scalar(
            GraphQLScalarType.newScalar()
                .name("ObjectId")
                .description("MongoDB ObjectId as 24-char hex string")
                .coercing(ObjectIdScalar())
                .build()
        )
    }
}
