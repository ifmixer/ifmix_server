package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreUploadRecord.Companion.CORE_UPLOAD_RECORD
import com.ifmix.api.core.entity.storage.UploadRecord
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_UPLOAD_RECORD,
        idField = CORE_UPLOAD_RECORD.ID,
        appIdField = CORE_UPLOAD_RECORD.APP_ID,
        type = UploadRecord::class.java,
    )

    fun insert(ctx: SvcCtx, record: UploadRecord) {
        crud.insert(ctx, record)
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): UploadRecord? =
        crud.findById(ctx, appId, id)
}
