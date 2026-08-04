package com.ifmix.api.core.modules.scan

import com.ifmix.api.core.infra.http.OperationContext

/**
 * 扫描输入中的单个媒体项。
 * 后续可扩展：category、userHint 等。
 */
data class ScanMediaItem(
    /** 预签名下载 URL（供 AI 模型访问） */
    val imageUrl: String,
    /** MIME 类型（如 image/jpeg, image/png） */
    val mediaType: String,
)

/**
 * ScanRunner 的输入 DTO。
 */
data class ScanInput(
    /** 一张或多张图片 */
    val items: List<ScanMediaItem>,
)

/**
 * AI 扫描服务接口。
 *
 * 负责将上传的图像送入 AI 模型进行识别/分析。
 * 同步执行，Virtual Threads 下不阻塞平台线程。
 */
interface ScanRunner {

    /**
     * 执行扫描。
     *
     * @param ctx 操作上下文
     * @param input 扫描输入（含图片 URL 和 mediaType）
     * @return 扫描结果
     */
    fun run(ctx: OperationContext, input: ScanInput): ScanResult
}
