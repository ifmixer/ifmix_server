package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppInfo
import com.ifmix.api.core.entity.app.id
import com.ifmix.api.core.entity.app.slug
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppInfoRepository {
    companion object { private val tpl = CrudRepoTemplate(AppInfo::class) }

    fun findBySlug(mc: ModuleCtx, slug: String): AppInfo? {
        return mc.sql.createQuery(AppInfo::class) {
            where(table.slug eq slug)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    /** 全量 appId（阶段 6 清理任务按 app 逐个扫描候选）。 */
    fun findAllIds(mc: ModuleCtx): List<UUID> =
        mc.sql.createQuery(AppInfo::class) {
            select(table.id)
        }.execute()
}
