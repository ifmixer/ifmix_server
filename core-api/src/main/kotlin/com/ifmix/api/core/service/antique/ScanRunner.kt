package com.ifmix.api.core.service.antique

import com.ifmix.api.core.infra.http.OperationContext

/**
 * 古物扫描执行器接口。
 *
 * 负责将上传的图像送入 AI 模型进行识别/分析。
 * 当前返回 stub ScanResult，后续接入实际模型调用。
 */
interface ScanRunner {

    /**
     * 执行扫描。
     *
     * @param ctx 请求上下文
     * @param imageUrl 预签名上传后的图片 URL
     * @return 扫描结果（可能为异步 pending）
     */
    suspend fun run(ctx: OperationContext, imageUrl: String): ScanResult
}
