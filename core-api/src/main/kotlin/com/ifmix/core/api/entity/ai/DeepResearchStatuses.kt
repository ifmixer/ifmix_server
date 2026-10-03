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
    /** 查询惰性判定：IN_PROGRESS 超 5 min 置 FAILED。 */
    const val TIMEOUT = "TIMEOUT"
    /** executor 提交失败（进程关闭/资源拒绝）：mutation 捕获后 CAS 置 FAILED 并返回终态。 */
    const val TASK_SUBMISSION_FAILED = "TASK_SUBMISSION_FAILED"
}
