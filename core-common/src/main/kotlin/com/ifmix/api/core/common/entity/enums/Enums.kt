package com.ifmix.api.core.common.entity.enums

import com.ifmix.api.core.common.infra.http.OperationContext

/**
 * 带数字编码的枚举公共接口。
 *
 * 所有存 DB 的枚举实现此接口，约定：
 * - code=0 为 UNKNOWN 兜底值
 * - 正常业务编码从 100 起步，同组连续，不同组间隔 100
 */
interface CodedEnum {
    val code: Int
}

/**
 * 扫描状态。
 */
enum class ScanStatus(override val code: Int) : CodedEnum {
    UNKNOWN(0),
    PENDING(100),
    PROCESSING(110),
    COMPLETED(200),
    FAILED(300);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ScanStatus = byCode[code] ?: UNKNOWN
    }
}

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
