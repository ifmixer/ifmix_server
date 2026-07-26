package com.ifmix.api.core.modules.antique

/**
 * 古物扫描结果。
 *
 * 由 ScanRunner 产出，后续可对接 AI 模型（如图像识别、年代判定等）。
 * 当前为 stub 结构，字段预留扩展空间。
 */
data class ScanResult(
    val scanId: String,
    val status: Status = Status.PENDING,
    val imageUrl: String? = null,
    val tags: List<String> = emptyList(),
    val confidence: Double? = null,
    val errorMessage: String? = null,
) {
    enum class Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
    }
}
