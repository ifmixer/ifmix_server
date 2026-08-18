package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreUploadRecord.Companion.CORE_UPLOAD_RECORD
import com.ifmix.api.core.model.UploadRecord
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository(private val crud: CrudOps) {

    fun insert(ctx: RepoContext, record: UploadRecord) {
        crud.insert(ctx, CORE_UPLOAD_RECORD, record)
    }

    fun findById(ctx: RepoContext, appId: UUID, id: UUID): UploadRecord? {
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
