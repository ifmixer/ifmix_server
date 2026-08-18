package com.ifmix.api.core.model.enums

import com.ifmix.api.core.model.enums.CodedEnum
/**
 * 反馈分类。
 */
enum class FeedbackCategory(override val code: Int) : CodedEnum {
    UNKNOWN(0),

    /** 喜欢这件藏品 */
    LIKED(100),

    /** 价格太高 */
    PRICE_TOO_HIGH(200),

    /** 价格太低 */
    PRICE_TOO_LOW(210),

    /** 价格缺失 */
    PRICE_MISSING(220),

    /** 鉴定有误 */
    WRONG_IDENTIFICATION(300),

    /** 功能建议 */
    FEATURE_REQUEST(400),

    /** 更多推荐 */
    MORE_RECOMMENDATIONS(410);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): FeedbackCategory = byCode[code] ?: UNKNOWN
    }
}
