package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.entity.storage.UploadRecord
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class UploadRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<UploadRecord>(sql, UploadRecord::class)
