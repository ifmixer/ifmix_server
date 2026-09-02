package com.ifmix.core.api.entity.common

/**
 * IAP 购买平台编码。
 *
 * 编码：0=UNKNOWN, 10=APPLE, 20=GOOGLE。
 */
object Platforms {
    const val APPLE = 10
    const val GOOGLE = 20

    fun fromCode(code: Int): Int = when (code) {
        APPLE -> APPLE
        GOOGLE -> GOOGLE
        else -> 0
    }
}
