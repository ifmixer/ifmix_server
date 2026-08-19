package com.ifmix.api.core.modules.storage.config

import com.ifmix.api.core.common.storage.NoopObjectStorage
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.common.storage.S3ObjectStorage
import com.ifmix.api.core.common.storage.StorageConfig
import com.ifmix.api.core.modules.storage.StorageFacade
import com.ifmix.api.core.modules.storage.handler.UploadRecordEntityHandler
import com.ifmix.api.core.modules.storage.repo.UploadRecordRepo
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * Storage 模块 bean 装配。
 *
 * - ObjectStorage：S3/R2 配置时注入 S3ObjectStorage，否则注入 NoopObjectStorage（与 AntiqueConfig 保持一致）。
 * - UploadRecordRepo：基于 MongoTemplate 的上传记录仓储。
 * - UploadRecordEntityHandler：实体构造 + 仓储调用。
 * - StorageFacade：模块门面，向 Fetcher 层暴露能力。
 */
@Configuration
class StorageModuleConfig {

    /** S3/R2 真实预签名实现。仅在 app.storage.type=s3 时加载。 */
    @Bean
    @ConditionalOnProperty(name = ["app.storage.type"], havingValue = "s3")
    fun s3ObjectStorage(presigner: S3Presigner, config: StorageConfig): ObjectStorage =
        S3ObjectStorage(presigner, config)

    /** 未配置存储时的回落实现（返回假 URL），保证本地/开发环境能启动。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage::class)
    fun noopObjectStorage(): ObjectStorage = NoopObjectStorage()

    @Bean
    @ConditionalOnMissingBean(UploadRecordRepo::class)
    fun uploadRecordRepo(mongo: org.springframework.data.mongodb.core.MongoTemplate): UploadRecordRepo =
        UploadRecordRepo(mongo)

    @Bean
    @ConditionalOnMissingBean(UploadRecordEntityHandler::class)
    fun uploadRecordEntityHandler(repo: UploadRecordRepo): UploadRecordEntityHandler =
        UploadRecordEntityHandler(repo)

    @Bean
    @ConditionalOnMissingBean(StorageFacade::class)
    fun storageFacade(
        objectStorage: ObjectStorage,
        uploadRecordHandler: UploadRecordEntityHandler,
    ): StorageFacade = StorageFacade(objectStorage, uploadRecordHandler)
}
