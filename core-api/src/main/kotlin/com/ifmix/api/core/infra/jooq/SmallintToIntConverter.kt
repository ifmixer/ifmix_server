package com.ifmix.api.core.infra.jooq

import org.jooq.Converter

/**
 * jOOQ Converter: DB SMALLINT (Short) ↔ Kotlin Int。
 * 全局应用于所有 SMALLINT 列，让 model 统一用 Int 而不是 Short。
 */
class SmallintToIntConverter : Converter<Short, Int> {
    override fun from(databaseObject: Short?): Int? = databaseObject?.toInt()
    override fun to(userObject: Int?): Short? = userObject?.toShort()
    override fun fromType(): Class<Short> = Short::class.javaObjectType
    override fun toType(): Class<Int> = Int::class.javaObjectType
}
