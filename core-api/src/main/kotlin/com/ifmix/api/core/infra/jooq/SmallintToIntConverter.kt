package com.ifmix.api.core.infra.jooq

import org.jooq.Converter

/**
 * PostgreSQL SMALLINT → Kotlin Int 转换器。
 * jOOQ codegen 通过 forcedType 注册后，所有 SMALLINT 列直接生成 Int 类型字段。
 */
class SmallintToIntConverter : Converter<Short, Int> {

    override fun from(db: Short?): Int? = db?.toInt()

    override fun to(user: Int?): Short? = user?.toShort()

    override fun fromType(): Class<Short> = Short::class.java

    override fun toType(): Class<Int> = Int::class.java
}
