package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAppInfo.Companion.CORE_APP_INFO
import com.ifmix.api.core.entity.app.AppInfo
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppInfoRepository(private val crud: CrudRepoOpsFactory) {

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
