package com.ifmix.api.core.common.storage

import com.ifmix.api.core.common.storage.StorageConfig
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.time.Instant

/**
 * 基于 AWS SDK v2 S3Presigner 的预签名 URL 实现。
 * 兼容 R2（Cloudflare）、MinIO、localstack 等 S3 兼容服务。
 */
class S3ObjectStorage(
    private val presigner: S3Presigner,
    private val config: StorageConfig,
) : ObjectStorage {

    override fun presignUpload(
        objectKey: String,
        contentType: String,
        duration: Duration,
    ): String {
        val putRequest = PutObjectRequest.builder()
            .bucket(config.bucketName)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(duration)
            .putObjectRequest(putRequest)
            .build()

        return presigner.presignPutObject(presignRequest).url().toString()
    }

    override fun presignDownload(
        objectKey: String,
        duration: Duration,
    ): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(config.bucketName)
            .key(objectKey)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(duration)
            .getObjectRequest(getRequest)
            .build()

        return presigner.presignGetObject(presignRequest).url().toString()
    }
}
