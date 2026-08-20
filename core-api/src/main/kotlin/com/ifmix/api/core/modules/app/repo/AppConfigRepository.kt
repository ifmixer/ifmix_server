package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.entity.app.appId
import com.ifmix.api.core.entity.app.androidPackageName
import com.ifmix.api.core.entity.app.appleBundleId
import com.ifmix.api.core.entity.app.createdAt
import com.ifmix.api.core.entity.app.enabled
import com.ifmix.api.core.entity.app.id
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppConfigRepository {
    companion object { private val tpl = CrudRepoTemplate(AppConfigRevision::class, appId = "appId") }

    fun findActiveByAppId(mc: ModuleCtx, appId: UUID): AppConfigRevision? {
        return mc.sql.createQuery(AppConfigRevision::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.enabled eq true)
            orderBy(table.createdAt.desc())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByBundleId(mc: ModuleCtx, bundleId: String): AppConfigRevision? {
        return mc.sql.createQuery(AppConfigRevision::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByAndroidPackage(mc: ModuleCtx, pkg: String): AppConfigRevision? {
        return mc.sql.createQuery(AppConfigRevision::class) {
            where(table.androidPackageName eq pkg)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun disableCurrentRevisions(mc: ModuleCtx, appId: UUID): Int {
        return mc.sql.createUpdate(AppConfigRevision::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.enabled eq true)
            set(table.enabled, false)
        }.execute()
    }

    fun updateEnabled(mc: ModuleCtx, revisionId: UUID, enabled: Boolean): Int {
        return mc.sql.createUpdate(AppConfigRevision::class) {
            where(table.id eq revisionId)
            set(table.enabled, enabled)
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: AppConfigRevision) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
