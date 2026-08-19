package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppInfo
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository

@Repository
class AppInfoRepository(sql: KSqlClient) : BaseCrudRepository<AppInfo>(sql, AppInfo::class) {

    fun findBySlug(slug: String): AppInfo? {
        return sql.createQuery(AppInfo::class) {
            where(table.slug eq slug)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
