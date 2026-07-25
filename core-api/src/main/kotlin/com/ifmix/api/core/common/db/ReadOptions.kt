package com.ifmix.api.core.common.db

/**
 * 读选项。
 * preferPrimary：是否强制走主库（写后回读避免副本延迟，read-your-writes）。
 * 未命中语义由方法决定：getById 抛 NOT_FOUND、findById 返回 null（Kotlin null 安全）。
 */
data class ReadOptions(val preferPrimary: Boolean) {
    companion object {
        val DEFAULT = ReadOptions(preferPrimary = false)
        val PRIMARY = ReadOptions(preferPrimary = true)
    }
}
