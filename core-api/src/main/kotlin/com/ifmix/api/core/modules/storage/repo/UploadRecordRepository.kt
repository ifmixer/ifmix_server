package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreUploadRecord.Companion.CORE_UPLOAD_RECORD
import com.ifmix.api.core.entity.storage.UploadRecord
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository(private val crud: CrudRepoOps) {

    fun insert(ctx: SvcCtx, record: UploadRecord) {
        crud.insert(ctx, CORE_UPLOAD_RECORD, record)
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): UploadRecord? {
        return crud.findById(
            ctx,
            CORE_UPLOAD_RECORD,
            CORE_UPLOAD_RECORD.APP_ID,
            CORE_UPLOAD_RECORD.ID,
            appId,
            id,
            UploadRecord::class.java,
        )
    }
}
