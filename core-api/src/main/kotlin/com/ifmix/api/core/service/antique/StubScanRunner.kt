package com.ifmix.api.core.service.antique

import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext

/**
 * 占位 ScanRunner：未接入真实 AI 时的回落实现。
 * 返回一个 COMPLETED 的假结果，不调用任何模型。
 * 生产用 SpringAiScanRunner（AiConfig，@Primary）覆盖。
 */
class StubScanRunner : ScanRunner {

    override fun run(ctx: OperationContext, input: ScanInput): ScanResult =
        ScanResult(
            scanId = UuidV7.generate().toString(),
            status = ScanStatus.COMPLETED,
            isAntique = false,
            name = "stub (${input.items.size} images)",
        )
}
