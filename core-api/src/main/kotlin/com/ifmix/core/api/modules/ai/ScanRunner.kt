package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.dto.ai.ScanInput

/**
 * AI 扫描服务接口。
 *
 * 成功返回 AI 解析的 JSON Map；失败直接抛异常。
 */
interface ScanRunner {
    fun run(ctx: OperationContext, input: ScanInput): Map<String, Any?>
}
