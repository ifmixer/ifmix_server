package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.MappedSuperclass

/**
 * String 主键（slug 即主键）。创建后不可变，格式受控（见 RequestParser.parseProjectId）。
 */
@MappedSuperclass
interface StringIdProps {
    @Id
    val id: String
}
