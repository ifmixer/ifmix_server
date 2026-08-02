package com.ifmix.api.core.repository.appconfig

import com.ifmix.api.core.entity.appconfig.AppConfigVersion
import com.ifmix.api.core.entity.appconfig.appId
import com.ifmix.api.core.entity.appconfig.appleBundleId
import com.ifmix.api.core.entity.appconfig.androidPackageName
import com.ifmix.api.core.entity.appconfig.enabled
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository(sql: KSqlClient) : BaseAppCrudRepository<AppConfigVersion>(sql, AppConfigVersion::class) {

    /** Find current enabled config by appId. */
    fun findCurrentByAppId(repo: RepoContext, appId: UUID): AppConfigVersion? {
        return sql.createQuery(AppConfigVersion::class) {
            where(table.appId eq appId)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Apple bundle ID (enabled only) */
    fun findByBundleId(repo: RepoContext, bundleId: String): AppConfigVersion? {
        return sql.createQuery(AppConfigVersion::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Android package name (enabled only) */
    fun findByAndroidPackage(repo: RepoContext, pkg: String): AppConfigVersion? {
        return sql.createQuery(AppConfigVersion::class) {
            where(table.androidPackageName eq pkg)
            where(table.enabled eq true)
            select(table)
        }.fetchOneOrNull()
    }
}
