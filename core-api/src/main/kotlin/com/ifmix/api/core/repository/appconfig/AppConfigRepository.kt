package com.ifmix.api.core.repository.appconfig

import com.ifmix.api.core.entity.appconfig.AppConfig
import com.ifmix.api.core.entity.appconfig.appId
import com.ifmix.api.core.entity.appconfig.appleBundleId
import com.ifmix.api.core.entity.appconfig.androidPackageName
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository(sql: KSqlClient,) : BaseAppCrudRepository<AppConfig>(sql, AppConfig::class) {

    /** Find current (non-deleted) config by appId. @LogicalDeleted auto-filters. */
    fun findCurrentByAppId(appId: UUID): AppConfig? {
        return sql.createQuery(AppConfig::class) {
            where(table.appId eq appId)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Apple bundle ID */
    fun findByBundleId(bundleId: String): AppConfig? {
        return sql.createQuery(AppConfig::class) {
            where(table.appleBundleId eq bundleId)
            select(table)
        }.fetchOneOrNull()
    }

    /** Find config by Android package name */
    fun findByAndroidPackage(pkg: String): AppConfig? {
        return sql.createQuery(AppConfig::class) {
            where(table.androidPackageName eq pkg)
            select(table)
        }.fetchOneOrNull()
    }
}
