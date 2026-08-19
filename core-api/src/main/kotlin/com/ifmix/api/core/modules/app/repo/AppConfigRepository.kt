package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.entity.app.appId
import com.ifmix.api.core.entity.app.appleBundleId
import com.ifmix.api.core.entity.app.androidPackageName
import com.ifmix.api.core.entity.app.enabled
import com.ifmix.api.core.entity.app.createdAt
import com.ifmix.api.core.entity.app.id
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository(sql: KSqlClient) : BaseAppCrudRepository<AppConfigRevision>(sql, AppConfigRevision::class) {

    fun findActiveByAppId(ctx: SvcCtx, appId: UUID): AppConfigRevision? {
        return ctx.ctx.sql.createQuery(AppConfigRevision::class) {
            where(appId eq appId)
            where(enabled eq true)
            orderBy(createdAt.desc())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByBundleId(ctx: SvcCtx, bundleId: String): AppConfigRevision? {
        return ctx.ctx.sql.createQuery(AppConfigRevision::class) {
            where(appleBundleId eq bundleId)
            where(enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByAndroidPackage(ctx: SvcCtx, pkg: String): AppConfigRevision? {
        return ctx.ctx.sql.createQuery(AppConfigRevision::class) {
            where(androidPackageName eq pkg)
            where(enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun disableCurrentRevisions(ctx: SvcCtx, appId: UUID): Int {
        return ctx.ctx.sql.createUpdate(AppConfigRevision::class) {
            where(appId eq appId)
            where(enabled eq true)
            set(enabled, false)
        }.execute()
    }

    fun updateEnabled(ctx: SvcCtx, revisionId: UUID, enabled: Boolean): Int {
        return ctx.ctx.sql.createUpdate(AppConfigRevision::class) {
            where(id eq revisionId)
            set(enabled, enabled)
        }.execute()
    }
}
