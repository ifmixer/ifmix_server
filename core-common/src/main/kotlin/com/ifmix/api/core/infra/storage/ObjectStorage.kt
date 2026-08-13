package com.ifmix.api.core.infra.storage

import java.time.Duration

/**
 * 对象存储抽象：统一预签名 URL 能力，底层可以是 S3、R2 或任何兼容 S3 的服务。
 */
interface ObjectStorage {

    /**
     * 生成预签名上传 URL。
     *
     * @param objectKey 对象键（如 "antique/scan-20240101-abc.png"）
     * @param contentType 内容类型（如 "image/png"）
     * @param duration 签名有效期
     * @return 预签名 URL 字符串
     */
    fun presignUpload(
        objectKey: String,
        contentType: String,
        duration: Duration = Duration.ofMinutes(5),
    ): String

    /**
     * 生成预签名下载 URL。
     */
    fun presignDownload(
        objectKey: String,
        duration: Duration = Duration.ofHours(1),
    ): String

    /**
     * 直接上传字节到对象存储。
     *
     * @param objectKey 对象键
     * @param data 文件字节
     * @param contentType MIME 类型
     */
    fun upload(objectKey: String, data: ByteArray, contentType: String)
}
