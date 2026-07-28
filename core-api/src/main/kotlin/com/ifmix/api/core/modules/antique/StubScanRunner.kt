package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.http.RequestContext
import java.util.UUID

/**
 * 占位 ScanRunner：未接入真实 AI（app.storage.type != s3 / 无 Agnes key）时的回落实现，
 * 仅供本地/开发环境启动。返回一个 COMPLETED 的假结果，不调用任何模型。
 * 生产用 SpringAiScanRunner（AiConfig，@Primary）覆盖。
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
