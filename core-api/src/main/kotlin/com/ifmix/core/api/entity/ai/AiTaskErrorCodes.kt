package com.ifmix.core.api.entity.ai

/** Scan 与 DeepResearch 共用的异步任务技术错误码。 */
object AiTaskErrorCodes {
    /** AI 调用本身抛异常。 */
    const val AI_FAILED = "AI_FAILED"
    /** 查询惰性判定：IN_PROGRESS 超过观察期限。 */
    const val TIMEOUT = "TIMEOUT"
    /** executor 提交失败。 */
    const val TASK_SUBMISSION_FAILED = "TASK_SUBMISSION_FAILED"
    /** 后台任务未预期异常。 */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
