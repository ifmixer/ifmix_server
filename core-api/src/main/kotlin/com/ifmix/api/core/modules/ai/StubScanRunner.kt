package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.common.http.RequestContext
import java.util.UUID

/**
 * Stub ScanRunner：未接入真实 AI 时的回落实现，仅供本地/开发环境启动。
 * 返回一个 COMPLETED 的假结果，不调用任何模型。
 */
class StubScanRunner : ScanRunner {

    override suspend fun run(ctx: RequestContext, imageUrl: String): ScanResult =
        ScanResult(
            scanId = UUID.randomUUID().toString(),
            status = ScanResult.Status.COMPLETED,
            imageUrl = imageUrl,
            isAntique = false,
            name = "stub",
        )
}
