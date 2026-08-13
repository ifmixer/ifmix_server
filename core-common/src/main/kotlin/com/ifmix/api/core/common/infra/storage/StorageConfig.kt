package com.ifmix.api.core.common.infra.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * S3/R2 存储配置。
 *
 * endpoint + region + accessKey/secretKey 从 application.yml 读取（app.storage.*）。
 * 非 amazonaws.com 端点自动走 path-style access（兼容 R2 / MinIO / localstack）。
 */
@Configuration
class StorageConfig(
    @Value("\${app.storage.region:us-east-1}") private val region: String,
    @Value("\${app.storage.endpoint:http://localhost:9000}") private val endpoint: String,
    @Value("\${app.storage.access-key:minioadmin}") private val accessKey: String,
    @Value("\${app.storage.secret-key:minioAdmin}") private val secretKey: String,
    @Value("\${app.storage.bucket:ugcdev}") private val bucket: String,
    /** R2 自定义域名（如 https://u1dev.ifmix.com）。配置后 presignDownload 返回公开 URL，不带签名参数。 */
    @Value("\${app.storage.public-url:}") private val publicUrl: String,
) {

    private fun credentials() = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey),
    )

    private fun isCustomEndpoint() = !endpoint.contains("amazonaws.com")

    @Bean
    fun s3Presigner(): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials())

        if (isCustomEndpoint()) {
            builder.endpointOverride(java.net.URI.create(endpoint))
            builder.serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(true).build()
            )
        }

        return builder.build()
    }

    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials())

        if (isCustomEndpoint()) {
            builder.endpointOverride(java.net.URI.create(endpoint))
            builder.forcePathStyle(true)
        }

        return builder.build()
    }

    val bucketName: String get() = bucket

    /** 公开访问 URL 前缀（不带尾部 /）。为空表示不使用自定义域名。 */
    val publicBaseUrl: String? get() = publicUrl.trimEnd('/').ifEmpty { null }
}
