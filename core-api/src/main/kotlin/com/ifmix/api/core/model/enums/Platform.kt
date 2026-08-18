package com.ifmix.api.core.model.enums

/**
 * IAP 购买平台。
 *
 * 编码：0=UNKNOWN, 100=APPLE, 200=GOOGLE。
 */
enum class Platform(override val code: Int) : CodedEnum {
    UNKNOWN(0),
    APPLE(100),
    GOOGLE(200);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Platform = byCode[code] ?: UNKNOWN
    }
}
