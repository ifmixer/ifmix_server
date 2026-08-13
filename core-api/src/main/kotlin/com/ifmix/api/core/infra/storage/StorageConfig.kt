package com.ifmix.api.core.infra.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 单个 bucket 配置。
 */
data class BucketConfig(
    /** S3 bucket 名称（如 "ugcdev"、"staticdev"） */
    var bucketName: String = "",
    /** 公开 CDN 域名前缀（如 https://u1dev.ifmix.com）。为空表示该 bucket 不支持公开访问。 */
    var publicUrl: String = "",
) {
    val publicBaseUrl: String? get() = publicUrl.trimEnd('/').ifEmpty { null }
}

/**
 * S3/R2 存储配置属性，支持多 bucket。
 *
 * 所有 bucket 共享同一 endpoint/credentials（同一个 R2 账号或 S3 账号），
 * 仅 bucket name 和 public-url 不同。
 *
 * YAML 示例:
 * ```
 * app.storage:
 *   type: s3
 *   region: auto
 *   endpoint: https://xxx.r2.cloudflarestorage.com
 *   access-key: xxx
 *   secret-key: xxx
 *   buckets:
 *     ugc:
 *       bucket-name: ugcdev
 *       public-url: https://u1dev.ifmix.com
 *     static:
 *       bucket-name: staticdev
 *       public-url: https://s1dev.ifmix.com
 * ```
 */
@ConfigurationProperties(prefix = "app.storage")
class StorageConfig {
    var type: String = "none"
    var region: String = "us-east-1"
    var endpoint: String = "http://localhost:9000"
    var accessKey: String = "minioadmin"
    var secretKey: String = "minioadmin"
    /** 多 bucket 配置 */
    var buckets: MutableMap<String, BucketConfig> = mutableMapOf()

    fun getBucketConfig(id: String): BucketConfig {
        return buckets[id]
            ?: throw IllegalArgumentException("Storage bucket '$id' not configured. Available: ${buckets.keys}")
    }
}

/**
 * S3 客户端 bean 配置。
 */
@Configuration
@EnableConfigurationProperties(StorageConfig::class)
class StorageBeanConfig(private val storageConfig: StorageConfig) {

    private fun credentials() = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(storageConfig.accessKey, storageConfig.secretKey),
    )

    private fun isCustomEndpoint() = !storageConfig.endpoint.contains("amazonaws.com")

    @Bean
    fun s3Presigner(): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(storageConfig.region))
            .credentialsProvider(credentials())

        if (isCustomEndpoint()) {
            builder.endpointOverride(java.net.URI.create(storageConfig.endpoint))
            builder.serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(true).build()
            )
        }

        return builder.build()
    }

    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(storageConfig.region))
            .credentialsProvider(credentials())

        if (isCustomEndpoint()) {
            builder.endpointOverride(java.net.URI.create(storageConfig.endpoint))
            builder.forcePathStyle(true)
        }

        return builder.build()
    }
}
