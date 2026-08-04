package com.ifmix.api.core.repository.appconfig

import com.ifmix.api.core.entity.appconfig.AppConfigRevision
import com.ifmix.api.core.entity.appconfig.appId
import com.ifmix.api.core.entity.appconfig.appleBundleId
import com.ifmix.api.core.entity.appconfig.androidPackageName
import com.ifmix.api.core.entity.appconfig.enabled
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository(sql: KSqlClient) : BaseAppCrudRepository<AppConfigRevision>(sql, AppConfigRevision::class) {

    /** Find current enabled config by appId. Throws if not found. */
    fun mustFindCurrentRevision(ctx: OperationContext): AppConfigRevision {
        return findCurrentRevision(ctx)
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "AppConfigRevision not found")
    }

    fun findCurrentRevision(ctx: OperationContext): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.appId eq ctx.mustGetAppId())
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Apple bundle ID (enabled only) */
    fun findByBundleId(ctx: OperationContext, bundleId: String): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Android package name (enabled only) */
    fun findByAndroidPackage(ctx: OperationContext, pkg: String): AppConfigRevision? {
        return sql.createQuery(AppConfigRevision::class) {
            where(table.androidPackageName eq pkg)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /** Disable all current enabled revisions for a given appId. */
    fun disableCurrentRevisions(ctx: OperationContext, appId: UUID): Int {
        return sql.createUpdate(AppConfigRevision::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            set(table.enabled, false)
        }.execute()
    }

    /** Insert a new revision row. */
    fun createNewRevision(ctx: OperationContext, input: AppConfigRevisionCreateInput): AppConfigRevision {
        val entity = input.toEntity {
            id = UuidV7.generate()
        }
        return sql.entities.save(entity) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity
    }

    /** Update enabled status of a specific revision. */
    fun updateEnabled(ctx: OperationContext, revisionId: UUID, enabled: Boolean): Int {
        return sql.createUpdate(AppConfigRevision::class) {
            where(table.getId<UUID>() eq revisionId)
            set(table.enabled, enabled)
        }.execute()
    }
}
