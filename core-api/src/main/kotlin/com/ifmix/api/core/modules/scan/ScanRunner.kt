package com.ifmix.api.core.modules.scan

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.dto.scan.ScanInput

/**
 * AI 扫描服务接口。
 *
 * 成功返回 AI 解析的 JSON Map；失败直接抛异常。
 */
interface ScanRunner {
    fun run(ctx: OperationContext, input: ScanInput): Map<String, Any?>
}
