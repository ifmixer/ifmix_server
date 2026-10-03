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
