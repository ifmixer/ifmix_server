package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppInfo
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import com.ifmix.api.core.entity.app.slug

@Repository
class AppInfoRepository(sql: KSqlClient) : BaseCrudRepository<AppInfo>(sql, AppInfo::class) {

    fun findBySlug(ctx: ModuleCtx, slug: String): AppInfo? {
        return ctx.sql.createQuery(AppInfo::class) {
            where(table.slug eq slug)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
