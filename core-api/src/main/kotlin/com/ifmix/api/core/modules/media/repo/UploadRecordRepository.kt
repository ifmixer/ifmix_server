package com.ifmix.api.core.modules.media.repo

import com.ifmix.api.core.entity.media.UploadRecord
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository {
    companion object { private val tpl = AppCrudRepoTemplate(UploadRecord::class) }

    fun save(mc: ModuleCtx, entity: UploadRecord) = tpl.save(mc, entity)
}
