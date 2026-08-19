package com.ifmix.api.core.graphql.common.scalar

import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import org.bson.types.ObjectId

/**
 * GraphQL ObjectId scalar：映射到 [ObjectId]，序列化为 24 位 hex 字符串。
 */
class ObjectIdScalar : Coercing<ObjectId, String> {

    override fun serialize(dataFetcherResult: Any): String {
        return when (dataFetcherResult) {
            is ObjectId -> dataFetcherResult.toHexString()
            is String -> dataFetcherResult
            else -> throw CoercingSerializeException(
                "Expected ObjectId or String but got ${dataFetcherResult::class.simpleName}"
            )
        }
    }

    override fun parseValue(input: Any): ObjectId {
        val hex = when (input) {
            is String -> input
            else -> throw CoercingParseValueException("Expected String but got ${input::class.simpleName}")
        }
        return ObjectId(hex)
    }

    override fun parseLiteral(input: Any): ObjectId {
        return when (input) {
            is StringValue -> ObjectId(input.value)
            else -> throw CoercingParseLiteralException("Expected StringValue but got ${input::class.simpleName}")
        }
    }
}
