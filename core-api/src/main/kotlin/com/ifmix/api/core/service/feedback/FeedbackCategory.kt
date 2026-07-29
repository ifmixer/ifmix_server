package com.ifmix.api.core.service.feedback

/** 反馈类别枚举。 */
enum class FeedbackCategory {
    /** 喜欢这件藏品 */
    LIKED,

    /** 价格太高 */
    PRICE_TOO_HIGH,

    /** 价格太低 */
    PRICE_TOO_LOW,

    /** 价格缺失 */
    PRICE_MISSING,

    /** 鉴定有误 */
    WRONG_IDENTIFICATION,

    /** 功能建议 */
    FEATURE_REQUEST,

    /** 更多推荐 */
    MORE_RECOMMENDATIONS,
}
