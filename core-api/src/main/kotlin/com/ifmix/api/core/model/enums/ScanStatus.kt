package com.ifmix.api.core.model.enums

import com.ifmix.api.core.infra.http.OperationContext

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
