package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.common.http.RequestContext

/**
 * AI 扫描执行器接口。
 *
 * 负责将上传的图像送入 AI 模型进行识别/分析。
 */
interface ScanRunner {

    /**
     * 执行扫描。
     *
     * @param ctx 请求上下文
     * @param imageUrl 预签名上传后的图片 URL
     * @return 扫描结果（可能为异步 pending）
     */
    suspend fun run(ctx: RequestContext, imageUrl: String): ScanResult
}
