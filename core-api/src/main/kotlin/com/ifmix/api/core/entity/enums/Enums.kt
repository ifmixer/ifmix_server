package com.ifmix.api.core.entity.enums

import org.babyfish.jimmer.sql.EnumItem
import org.babyfish.jimmer.sql.EnumType

/**
 * 扫描状态。
 *
 * 编码规则：0 保留不用；同组连续；不同组间隔 10。
 */
@EnumType(EnumType.Strategy.ORDINAL)
enum class ScanStatus(val code: Int) {
    @EnumItem(ordinal = 100)
    PENDING(100),

    @EnumItem(ordinal = 110)
    PROCESSING(110),

    @EnumItem(ordinal = 200)
    COMPLETED(200),

    @EnumItem(ordinal = 300)
    FAILED(300);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ScanStatus = byCode[code]
            ?: throw IllegalArgumentException("Unknown ScanStatus code: $code")
    }
}

/**
 * Agnes Key 类型。
 *
 * 编码规则：10=个人, 20=企业。
 */
@EnumType(EnumType.Strategy.ORDINAL)
enum class AgnesKeyType(val code: Int) {
    @EnumItem(ordinal = 100)
    PERSONAL(100),

    @EnumItem(ordinal = 200)
    ENTERPRISE(200);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): AgnesKeyType = byCode[code]
            ?: throw IllegalArgumentException("Unknown AgnesKeyType code: $code")
    }
}

/**
 * 反馈分类。
 *
 * 编码规则：10=LIKED, 20-22=价格相关, 30=鉴定, 40-41=功能请求。
 */
@EnumType(EnumType.Strategy.ORDINAL)
enum class FeedbackCategory(val code: Int) {
    /** 喜欢这件藏品 */
    @EnumItem(ordinal = 100)
    LIKED(100),

    /** 价格太高 */
    @EnumItem(ordinal = 200)
    PRICE_TOO_HIGH(200),

    /** 价格太低 */
    @EnumItem(ordinal = 210)
    PRICE_TOO_LOW(210),

    /** 价格缺失 */
    @EnumItem(ordinal = 220)
    PRICE_MISSING(220),

    /** 鉴定有误 */
    @EnumItem(ordinal = 300)
    WRONG_IDENTIFICATION(300),

    /** 功能建议 */
    @EnumItem(ordinal = 400)
    FEATURE_REQUEST(400),

    /** 更多推荐 */
    @EnumItem(ordinal = 410)
    MORE_RECOMMENDATIONS(410);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): FeedbackCategory = byCode[code]
            ?: throw IllegalArgumentException("Unknown FeedbackCategory code: $code")
    }
}
