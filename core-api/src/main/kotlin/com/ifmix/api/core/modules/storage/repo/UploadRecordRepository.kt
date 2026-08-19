package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreUploadRecord.Companion.CORE_UPLOAD_RECORD
import com.ifmix.api.core.entity.storage.UploadRecord
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            UploadRecord::id.name to CORE_UPLOAD_RECORD.ID,
            UploadRecord::appId.name to CORE_UPLOAD_RECORD.APP_ID,
            UploadRecord::installId.name to CORE_UPLOAD_RECORD.INSTALL_ID,
            UploadRecord::userId.name to CORE_UPLOAD_RECORD.USER_ID,
            UploadRecord::objectKey.name to CORE_UPLOAD_RECORD.OBJECT_KEY,
            UploadRecord::contentType.name to CORE_UPLOAD_RECORD.CONTENT_TYPE,
            UploadRecord::category.name to CORE_UPLOAD_RECORD.CATEGORY,
            UploadRecord::clientIp.name to CORE_UPLOAD_RECORD.CLIENT_IP,
            UploadRecord::createdAt.name to CORE_UPLOAD_RECORD.CREATED_AT,
        )
    }

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
