package com.ifmix.api.core.model.enums

/**
 * Agnes Key 类型。
 */
enum class AgnesKeyType(override val code: Int) : CodedEnum {
    UNKNOWN(0),
    PERSONAL(100),
    ENTERPRISE(200);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): AgnesKeyType = byCode[code] ?: UNKNOWN
    }
}
