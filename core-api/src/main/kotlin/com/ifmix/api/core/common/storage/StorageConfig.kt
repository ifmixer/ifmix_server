package com.ifmix.api.core.common.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
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
@ConditionalOnProperty(
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
    @Value("\${app.storage.public-url:}") private val publicUrl: String,
) {
    /** 公开访问基础 URL，空字符串返回 null；自动去除末尾斜杠。 */
    val publicBaseUrl: String? get() = publicUrl.trimEnd('/').takeIf { it.isNotEmpty() }

    @Bean
    fun s3Presigner(): S3Presigner {
        val credentials = privateCredentialsProvider()

        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)

        // 非标准 AWS 端点走自定义 endpoint 并启用 path-style access
        if (!endpoint.contains("amazonaws.com")) {
            val serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .checksumValidationEnabled(false)
                .build()
            builder.endpointOverride(java.net.URI.create(endpoint))
                .serviceConfiguration(serviceConfig)
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

    /** 校验凭据有效性，无效则返回 null（调用方需自行处理）。 */
    private fun privateCredentialsProvider(): StaticCredentialsProvider? {
        val hasCredentials = accessKey.isNotBlank() && secretKey.isNotBlank()
        return if (hasCredentials) {
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey),
            )
        } else {
            null
        }
    }

    val bucketName: String get() = bucket
}
