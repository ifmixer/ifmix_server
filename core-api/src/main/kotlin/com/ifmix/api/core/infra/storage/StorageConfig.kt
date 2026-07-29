package com.ifmix.api.core.infra.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * S3/R2 预签名 URL 配置。
 * 仅在 app.storage.type=s3 时生效（默认不加载，避免测试环境缺 AWS 凭据）。
 *
 * endpoint + region + accessKey/secretKey 从 application.yml 读取（app.storage.*）。
 * 当 endpoint 以 localhost 开头时自动走 path-style access（兼容 MinIO / localstack）。
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = arrayOf("app.storage.type"),
    havingValue = "s3",
    matchIfMissing = false,
)
class StorageConfig(
    @Value("\${app.storage.region:us-east-1}") private val region: String,
    @Value("\${app.storage.endpoint:http://localhost:9000}") private val endpoint: String,
    @Value("\${app.storage.access-key:minioadmin}") private val accessKey: String,
    @Value("\${app.storage.secret-key:minioAdmin}") private val secretKey: String,
    @Value("\${app.storage.bucket:ifmix}") private val bucket: String,
) {
    @Bean
    fun s3Presigner(): S3Presigner {
        val credentials = if (accessKey != "minioadmin" || secretKey != "minioAdmin") {
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey),
            )
        } else {
            DefaultCredentialsProvider.create()
        }

        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)

        // 非标准 AWS 端点走自定义 endpoint
        if (!endpoint.contains("amazonaws.com")) {
            builder.endpointOverride(java.net.URI.create(endpoint))
        }

        return builder.build()
    }

    @Bean
    fun s3Client(builder: S3ClientBuilder): S3Client {
        val clientBuilder = S3Client.builder()
            .region(Region.of(region))

        if (!endpoint.contains("amazonaws.com")) {
            clientBuilder.endpointOverride(java.net.URI.create(endpoint))
                .forcePathStyle(true)
        }

        return clientBuilder.build()
    }

    val bucketName: String get() = bucket
}
