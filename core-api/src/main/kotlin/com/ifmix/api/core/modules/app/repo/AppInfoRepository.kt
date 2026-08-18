package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAppInfo.Companion.CORE_APP_INFO
import com.ifmix.api.core.entity.app.AppInfo
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppInfoRepository(private val crud: CrudRepoOps) {

    fun findById(id: UUID): AppInfo? =
        crud.findById(
            ctx = SvcCtx.DEFAULT,
            table = CORE_APP_INFO,
            appIdField = CORE_APP_INFO.ID,
            idField = CORE_APP_INFO.ID,
            appId = id,
            id = id,
            type = AppInfo::class.java,
        )

    fun findBySlug(slug: String): AppInfo? =
        SvcCtx.DEFAULT.dsl
            .selectFrom(CORE_APP_INFO)
            .where(CORE_APP_INFO.SLUG.eq(slug))
            .fetchOneInto(AppInfo::class.java)
}
