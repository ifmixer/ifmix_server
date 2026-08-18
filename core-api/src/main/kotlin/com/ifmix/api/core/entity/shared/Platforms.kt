package com.ifmix.api.core.entity.shared

/**
 * IAP 购买平台编码。
 *
 * 编码：0=UNKNOWN, 100=APPLE, 200=GOOGLE。
 */
object Platforms {
    const val APPLE = 100
    const val GOOGLE = 200

    fun fromCode(code: Int): Int = when (code) {
        APPLE -> APPLE
        GOOGLE -> GOOGLE
        else -> 0
    }
}
