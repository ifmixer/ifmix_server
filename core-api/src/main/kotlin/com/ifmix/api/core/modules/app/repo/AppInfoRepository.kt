package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAppInfo.Companion.CORE_APP_INFO
import com.ifmix.api.core.entity.app.AppInfo
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppInfoRepository(private val crud: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            AppInfo::id.name to CORE_APP_INFO.ID,
            AppInfo::name.name to CORE_APP_INFO.NAME,
            AppInfo::description.name to CORE_APP_INFO.DESCRIPTION,
            AppInfo::slug.name to CORE_APP_INFO.SLUG,
            AppInfo::createdAt.name to CORE_APP_INFO.CREATED_AT,
            AppInfo::updatedAt.name to CORE_APP_INFO.UPDATED_AT,
        )
    }

    private val crudOps = crud.create(
        table = CORE_APP_INFO,
        idField = CORE_APP_INFO.ID,
        appIdField = null,
        type = AppInfo::class.java,
    )

    fun findById(id: UUID): AppInfo? =
        crudOps.findById(SvcCtx.DEFAULT, id)

    fun findBySlug(slug: String): AppInfo? =
        SvcCtx.DEFAULT.dsl
            .selectFrom(CORE_APP_INFO)
            .where(CORE_APP_INFO.SLUG.eq(slug))
            .fetchOneInto(AppInfo::class.java)
}
