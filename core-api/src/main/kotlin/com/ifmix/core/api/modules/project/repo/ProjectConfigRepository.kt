package com.ifmix.core.api.modules.project.repo

import com.ifmix.core.api.entity.project.ProjectConfigRevision
import com.ifmix.core.api.entity.project.projectId
import com.ifmix.core.api.entity.project.androidPackageName
import com.ifmix.core.api.entity.project.appleBundleId
import com.ifmix.core.api.entity.project.createdAt
import com.ifmix.core.api.entity.project.enabled
import com.ifmix.core.api.entity.project.id
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectConfigRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(ProjectConfigRevision::class, UUID::class) }

    fun findActiveByAppId(mc: ModuleCtx, projectId: String): ProjectConfigRevision? {
        return mc.sql.createQuery(ProjectConfigRevision::class) {
            where(table.projectId eq projectId)
            where(table.enabled eq true)
            orderBy(table.createdAt.desc())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByBundleId(mc: ModuleCtx, bundleId: String): ProjectConfigRevision? {
        return mc.sql.createQuery(ProjectConfigRevision::class) {
            where(table.appleBundleId eq bundleId)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByAndroidPackage(mc: ModuleCtx, packageName: String): ProjectConfigRevision? {
        return mc.sql.createQuery(ProjectConfigRevision::class) {
            where(table.androidPackageName eq packageName)
            where(table.enabled eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun disableCurrentRevisions(mc: ModuleCtx, projectId: String): Int {
        return mc.sql.createUpdate(ProjectConfigRevision::class) {
            where(table.projectId eq projectId)
            where(table.enabled eq true)
            set(table.enabled, false)
        }.execute()
    }

    fun updateEnabled(mc: ModuleCtx, revisionId: UUID, enabled: Boolean): Int {
        return mc.sql.createUpdate(ProjectConfigRevision::class) {
            where(table.id eq revisionId)
            set(table.enabled, enabled)
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: ProjectConfigRevision) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, projectId: String, id: UUID) = tpl.findById(mc, projectId, id)
    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.deleteById(mc, projectId, id)
    fun exists(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.exists(mc, projectId, id)
}
