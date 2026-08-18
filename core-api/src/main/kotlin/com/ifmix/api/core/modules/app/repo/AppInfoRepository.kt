package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreAppInfo.Companion.CORE_APP_INFO
import com.ifmix.api.core.model.AppInfo
import org.springframework.stereotype.Repository

@Repository
class AppInfoRepository(private val crud: CrudOps) {

    fun findById(id: java.util.UUID): AppInfo? =
        crud.findById(
            ctx = com.ifmix.api.core.infra.db.RepoContext.DEFAULT,
            table = CORE_APP_INFO,
            appIdField = CORE_APP_INFO.ID,
            idField = CORE_APP_INFO.ID,
            appId = id,
            id = id,
            type = AppInfo::class.java,
        )

    fun findBySlug(slug: String): AppInfo? =
        com.ifmix.api.core.infra.db.RepoContext.DEFAULT.dsl
            .selectFrom(CORE_APP_INFO)
            .where(CORE_APP_INFO.SLUG.eq(slug))
            .fetchOne()?.let { mapToModel(it) }

    private fun mapToModel(record: com.ifmix.api.core.jooq.tables.records.CoreAppInfoRecord): AppInfo =
        AppInfo(
            id = record.id!!,
            name = record.name,
            description = record.description,
            slug = record.slug ?: "",
            createdAt = record.createdAt ?: java.time.Instant.now(),
            updatedAt = record.updatedAt ?: java.time.Instant.now(),
        )
}
