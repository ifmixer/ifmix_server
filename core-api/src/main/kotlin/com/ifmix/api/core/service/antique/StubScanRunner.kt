package com.ifmix.api.core.service.antique

import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext

/**
 * 占位 ScanRunner：未接入真实 AI（app.storage.type != s3 / 无 Agnes key）时的回落实现，
 * 仅供本地/开发环境启动。返回一个 COMPLETED 的假结果，不调用任何模型。
 * 生产用 SpringAiScanRunner（AiConfig，@Primary）覆盖。
 */
class StubScanRunner : ScanRunner {

    override suspend fun run(ctx: OperationContext, imageUrl: String): ScanResult =
        ScanResult(
            scanId = UuidV7.generate().toString(),
            status = ScanStatus.COMPLETED,
            isAntique = false,
            name = "stub",
        )
}
