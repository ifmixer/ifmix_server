package com.ifmix.api.core.model.enums

import com.ifmix.api.core.model.enums.CodedEnum
import com.ifmix.api.core.infra.http.OperationContext

/**
 * 限额档位：按用户身份自动判定。
 *
 * 编码：0=UNKNOWN, 100=FREE, 200=PRO, 300=ENTERPRISE。
 */
enum class Tier(override val code: Int) : CodedEnum {
    UNKNOWN(0),
    FREE(100),
    PRO(200),
    ENTERPRISE(300);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Tier = byCode[code] ?: UNKNOWN

        /** 默认返回 FREE。 */
        fun from(ctx: OperationContext): Tier = FREE
    }
}
