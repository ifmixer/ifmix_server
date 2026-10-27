package com.ifmix.core.api.modules.ai.service

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 projectId 取提示词组（设计文档 §8.1）。
 *
 * 原先 [ScanPrompt] 是单例 `@Component`，构造时硬编码加载 antique 的模板 —— 第二个 app 接进来
 * 就会共用古董提示词。改为**按项目实例化并缓存**：未登记的 projectId 回落默认组（antique），
 * 老 app 行为逐字不变。
 *
 * 缓存是懒加载的：某个项目配置写错（提示词文件不存在）只在**该项目**首次扫描时炸，
 * 不会在 Spring 上下文启动时连带拖垮别的 app。
 */
@Component
class ScanPromptRegistry(
    private val props: ScanPromptProperties = ScanPromptProperties(),
) {

    private val cache = ConcurrentHashMap<String, ScanPrompt>()

    /** 取该项目的提示词组；projectId 缺失/未登记 → 回落默认组。 */
    fun forProject(projectId: String?): ScanPrompt {
        val key = projectId?.takeIf { it.isNotBlank() } ?: props.default
        return cache.computeIfAbsent(key) { build(it) }
    }

    private fun build(projectId: String): ScanPrompt {
        val cfg = props.projects[projectId] ?: props.projects[props.default]
        // 配置整段缺失（测试、最小环境）时保持改造前的行为：antique 模板 + v10。
            ?: return ScanPrompt(promptVersion = LEGACY_VERSION)
        return ScanPrompt(
            promptVersion = cfg.version,
            basicName = "${cfg.name}-basic",
            fullName = cfg.full ?: "${cfg.name}-deep-research",
            basicScanUsesFullTemplate = cfg.scanUsesFullTemplate,
        )
    }

    private companion object {
        const val LEGACY_VERSION = "v10"
    }
}
