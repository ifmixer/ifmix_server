package com.ifmix.core.api.entity.ai

/**
 * DeepResearch 任务状态编码（Int 全链路透传）。
 *
 * CREATED 为语义预留：实际落库直接写 IN_PROGRESS（mutation 创建即进行中）。
 */
object DeepResearchStatuses {
    const val CREATED = 10
    const val IN_PROGRESS = 20
    const val SUCCESS = 30
    const val FAILED = 40
}

/**
 * DeepResearch 稳定错误码（对外暴露，不含内部异常细节）。
 */
object DeepResearchErrorCodes {
    /** AI 调用本身抛异常。 */
    const val AI_FAILED = "AI_FAILED"
    /** AI 正常返回但 scan_status 非 SUCCESS/PARTIAL（error_details 含 scan_status 供补拍提示）。 */
    const val AI_STATUS_REJECTED = "AI_STATUS_REJECTED"
    /** 结果 doc 上传 R2 最终失败（重试耗尽）。 */
    const val R2_UPLOAD_FAILED = "R2_UPLOAD_FAILED"
    /** 查询惰性判定：IN_PROGRESS 超 10 min 置 FAILED。 */
    const val TIMEOUT = "TIMEOUT"
}
