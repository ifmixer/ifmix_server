package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.entity.app.appId
import com.ifmix.api.core.entity.app.appleBundleId
import com.ifmix.api.core.entity.app.androidPackageName
import com.ifmix.api.core.entity.app.id
import com.ifmix.api.core.entity.app.createdAt
import com.ifmix.api.core.entity.app.enabled
import com.ifmix.api.core.entity.app.AppConfigRevision.appId
import com.ifmix.api.core.entity.app.AppConfigRevision.enabled
import com.ifmix.api.core.entity.app.AppConfigRevision.createdAt
import com.ifmix.api.core.entity.app.AppConfigRevision.appleBundleId
import com.ifmix.api.core.entity.app.AppConfigRevision.androidPackageName
import com.ifmix.api.core.entity.app.AppConfigRevision.id
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository(sql: KSqlClient) : BaseAppCrudRepository<AppConfigRevision>(sql, AppConfigRevision::class) {

    fun findActiveByAppId(ctx: SvcCtx, appId: UUID): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            orderBy(table.createdAt.desc())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByBundleId(ctx: SvcCtx, bundleId: String): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByAndroidPackage(ctx: SvcCtx, pkg: String): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.androidPackageName eq pkg)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun disableCurrentRevisions(ctx: SvcCtx, appId: UUID): Int {
        return sql.createUpdate(AppConfigRevision::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            set(enabled, false)
        }.execute()
    }

    fun updateEnabled(ctx: SvcCtx, revisionId: UUID, enabled: Boolean): Int {
        return sql.createUpdate(AppConfigRevision::class) {
            where(table.id eq revisionId)
            set(enabled, enabled)
        }.execute()
    }
}
