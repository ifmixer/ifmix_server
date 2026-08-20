package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.entity.storage.UploadRecord
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository {
    companion object { private val tpl = CrudRepoTemplate(UploadRecord::class, appId = "appId") }

    fun save(mc: ModuleCtx, entity: UploadRecord) = tpl.save(mc, entity)
}
