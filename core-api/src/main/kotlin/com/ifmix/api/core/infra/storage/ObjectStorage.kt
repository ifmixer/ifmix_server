package com.ifmix.api.core.infra.storage

import java.time.Duration

/**
 * 对象存储抽象：统一预签名 URL 能力，底层可以是 S3、R2 或任何兼容 S3 的服务。
 *
 * 所有方法第一个参数 bucketId 标识目标 bucket（如 "ugc"、"static"），
 * 对应 application.yml 中 app.storage.buckets.{id} 的配置。
 */
interface ObjectStorage {

    /**
     * 生成预签名上传 URL。
     */
    fun presignUpload(
        bucketId: String,
        objectKey: String,
        contentType: String,
        duration: Duration = Duration.ofMinutes(5),
    ): String

    /**
     * 生成预签名下载 URL（私有 bucket 使用）。
     * 始终返回带签名参数的 URL，不受 public-url 配置影响。
     */
    fun presignDownload(
        bucketId: String,
        objectKey: String,
        duration: Duration = Duration.ofHours(1),
    ): String

    /**
     * 获取公开访问 URL（公开 bucket 使用，如 CDN 域名直接拼接）。
     * 要求该 bucket 配置了 public-url，否则抛异常。
     */
    fun getPublicUrl(bucketId: String, objectKey: String): String

    /**
     * 直接上传字节到对象存储。
     */
    fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String)
}
