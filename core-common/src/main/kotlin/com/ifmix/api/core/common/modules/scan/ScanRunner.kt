package com.ifmix.api.core.common.modules.scan

import com.ifmix.api.core.common.infra.http.OperationContext
import com.ifmix.api.core.common.modules.scan.dto.ScanInput

/**
 * AI 扫描服务接口。
 *
 * 成功返回 AI 解析的 JSON Map；失败直接抛异常。
 */
interface ScanRunner {
    fun run(ctx: OperationContext, input: ScanInput): Map<String, Any?>
}
