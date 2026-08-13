package com.ifmix.api.core.infra.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

/**
 * 基于 AWS SDK v2 S3Presigner 的预签名 URL 实现。
 * 兼容 R2（Cloudflare）、MinIO、localstack 等 S3 兼容服务。
 */
class S3ObjectStorage(
    private val presigner: S3Presigner,
    private val s3Client: S3Client,
    private val config: StorageConfig,
) : ObjectStorage {

    /** imageKey 以 / 开头是外部约定，S3 key 去掉前导 / */
    private fun s3Key(objectKey: String): String = objectKey.removePrefix("/")

    override fun presignUpload(
        bucketId: String,
        objectKey: String,
        contentType: String,
        duration: Duration,
    ): String {
        val cfg = config.getBucketConfig(bucketId)
        val putRequest = PutObjectRequest.builder()
            .bucket(cfg.bucketName)
            .key(s3Key(objectKey))
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(duration)
            .putObjectRequest(putRequest)
            .build()

        return presigner.presignPutObject(presignRequest).url().toString()
    }

    override fun presignDownload(
        bucketId: String,
        objectKey: String,
        duration: Duration,
    ): String {
        val cfg = config.getBucketConfig(bucketId)
        val getRequest = GetObjectRequest.builder()
            .bucket(cfg.bucketName)
            .key(s3Key(objectKey))
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(duration)
            .getObjectRequest(getRequest)
            .build()

        return presigner.presignGetObject(presignRequest).url().toString()
    }

    override fun getPublicUrl(bucketId: String, objectKey: String): String {
        val cfg = config.getBucketConfig(bucketId)
        val base = cfg.publicBaseUrl
            ?: throw IllegalStateException("Bucket '$bucketId' has no public-url configured")
        return "$base/${s3Key(objectKey)}"
    }

    override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) {
        val cfg = config.getBucketConfig(bucketId)
        val putRequest = PutObjectRequest.builder()
            .bucket(cfg.bucketName)
            .key(s3Key(objectKey))
            .contentType(contentType)
            .build()

        s3Client.putObject(putRequest, RequestBody.fromBytes(data))
    }
}
