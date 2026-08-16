package com.ifmix.api.core.graphql.common.scalar

import com.netflix.graphql.dgs.DgsScalar
import graphql.Scalars
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * GraphQL DateTime scalar → java.time.Instant
 *
 * DGS 12 extended scalars 自动注册了 graphql-java 标准的 DateTime，
 * 但本系统使用自定义实例以确保序列化/反序列化行为一致。
 */
@DgsScalar(name = "DateTime")
@Configuration
class DateTimeScalarConfig {

    @Bean
    fun dateTimeScalar(): GraphQLScalarType {
        return GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("ISO-8601 格式的 UTC 时间戳，序列化为毫秒时间戳或 ISO 字符串")
            .coercing(object : Coercing<Instant, Any> {
                override fun serialize(dataFetchEnvironment: Any): Any {
                    return when (dataFetchEnvironment) {
                        is Instant -> dataFetchEnvironment.toEpochMilli()
                        is Long -> Instant.ofEpochMilli(dataFetchEnvironment)
                        else -> throw IllegalArgumentException("Cannot serialize $dataFetchEnvironment to DateTime")
                    }
                }

                override fun parseValue(dataFetchEnvironment: Any): Instant {
                    return when (dataFetchEnvironment) {
                        is String -> Instant.parse(dataFetchEnvironment)
                        is Number -> Instant.ofEpochMilli(dataFetchEnvironment.toLong())
                        else -> throw IllegalArgumentException("Cannot parse $dataFetchEnvironment as DateTime")
                    }
                }

                override fun parseLiteral(dataFetchEnvironment: Any): Instant {
                    throw UnsupportedOperationException("Literal parsing not supported for DateTime scalar")
                }
            })
            .build()
    }
}
