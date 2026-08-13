package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.appconfig.AppConfigRevision
import com.ifmix.api.core.entity.appconfig.appId
import com.ifmix.api.core.entity.appconfig.appleBundleId
import com.ifmix.api.core.entity.appconfig.androidPackageName
import com.ifmix.api.core.entity.appconfig.enabled
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRevisionRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<AppConfigRevision>(clusterRegistry, AppConfigRevision::class) {

    fun mustFindCurrentRevision(ctx: RepoContext, appId: UUID): AppConfigRevision {
        return findCurrentRevision(ctx, appId)
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "AppConfigRevision not found")
    }

    fun findCurrentRevision(ctx: RepoContext, appId: UUID): AppConfigRevision? {
        return sql(ctx).createQuery(AppConfigRevision::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    fun findByBundleId(ctx: RepoContext, bundleId: String): AppConfigRevision? {
        return sql(ctx).createQuery(AppConfigRevision::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    fun findByAndroidPackage(ctx: RepoContext, pkg: String): AppConfigRevision? {
        return sql(ctx).createQuery(AppConfigRevision::class) {
            where(table.androidPackageName eq pkg)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    fun disableCurrentRevisions(ctx: RepoContext, appId: UUID): Int {
        return writerSql(ctx).createUpdate(AppConfigRevision::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            set(table.enabled, false)
        }.execute()
    }

    fun createNewRevision(ctx: RepoContext, input: AppConfigRevisionCreateInput): AppConfigRevision {
        val entity = input.toEntity {
            id = UuidV7.generate()
        }
        return writerSql(ctx).entities.save(entity) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity
    }

    fun updateEnabled(ctx: RepoContext, revisionId: UUID, enabled: Boolean): Int {
        return writerSql(ctx).createUpdate(AppConfigRevision::class) {
            where(table.getId<UUID>() eq revisionId)
            set(table.enabled, enabled)
        }.execute()
    }
}
