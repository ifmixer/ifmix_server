package com.ifmix.core.api.modules.ai.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 扫描提示词的项目配置（`app.ai.prompt.*`）。
 *
 * 背景：提示词原先由 [ScanPrompt] 在构造时硬编码加载 antique 的那一份，与 projectId 无关 ——
 * 第二个 app（tcgcard）一接进来就会共用古董提示词。改为**按 projectId 查表**：差异一律走配置，
 * 服务端不写 `if (projectId == "tcgcard")` 分支（设计文档 §4 反目标）。
 *
 * 未登记的 projectId 一律回落 [default]（antique），老 app 行为不变。
 *
 * 示例（application.yml）：
 * ```yaml
 * app:
 *   ai:
 *     prompt:
 *       default: antique
 *       projects:
 *         antique:
 *           name: antique-scan-system
 *           version: ${SCAN_PROMPT_VERSION:v10}
 *         tcgcard:
 *           name: tcgcard-scan-system
 *           full: tcgcard-scan-system-premium
 *           version: ${TCG_SCAN_PROMPT_VERSION:v1}
 *           scan-uses-full-template: true
 * ```
 */
@ConfigurationProperties(prefix = "app.ai.prompt")
data class ScanPromptProperties(
    /** 未登记的 projectId 回落到这一组（必须是 [projects] 里已注册的 key）。 */
    val default: String = DEFAULT_PROJECT,
    /** projectId → 该项目的提示词组。 */
    val projects: Map<String, ProjectPromptProperties> = emptyMap(),
) {

    data class ProjectPromptProperties(
        /**
         * 文件名前缀（不含 `-basic` / `-full` 后缀，也不含版本）：
         * basic 组 = `${name}-basic`，full 组 = [full] ?: `${name}-deep-research`。
         */
        val name: String = "antique-scan-system",
        /** 提示词版本，拼进文件名并随扫描结果落库。 */
        val version: String = "v10",
        /**
         * full 组的完整文件名（不含版本）。antique 沿用 `-deep-research`；
         * tcgcard 的 premium 提示词叫 `-premium`，用它覆盖即可。
         */
        val full: String? = null,
        /**
         * 普通扫描（ScanType.BASIC）是否直接走 full 组。
         * tcgcard v1 单次扫描就要同时产出 basic_result + premium_result（设计 §8.2），故为 true；
         * antique 的 premium 由 DeepResearch 异步任务产出，这里必须 false。
         */
        val scanUsesFullTemplate: Boolean = false,
    )

    companion object {
        const val DEFAULT_PROJECT = "antique"
    }
}
