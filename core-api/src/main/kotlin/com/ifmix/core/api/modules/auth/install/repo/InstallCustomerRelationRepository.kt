package com.ifmix.core.api.modules.auth.install.repo

import com.ifmix.core.api.entity.auth.install.InstallCustomerRelation
import com.ifmix.core.api.entity.auth.install.customerId
import com.ifmix.core.api.entity.auth.install.deletedAt
import com.ifmix.core.api.entity.auth.install.id
import com.ifmix.core.api.entity.auth.install.installId
import com.ifmix.core.api.entity.auth.install.projectId
import com.ifmix.core.api.entity.auth.install.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.ne
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class InstallCustomerRelationRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(InstallCustomerRelation::class, UUID::class) }

    fun save(mc: ModuleCtx, entity: InstallCustomerRelation): Boolean = tpl.save(mc, entity)

    /**
     * 按 (installId, customerId) 查关系——**含软删行**（用于 upsert 决定 插/复活）。
     * 用 filters{setBehavior(IGNORED)} 绕过 @LogicalDeleted 默认过滤，否则漏掉已软删行
     * → 误判不存在 → 插新行撞 UNIQUE(install_id, customer_id)。
     */
    fun findAnyByPair(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID): InstallCustomerRelation? =
        mc.sql.filters { setBehavior(InstallCustomerRelation::class, LogicalDeletedBehavior.IGNORED) }
            .createQuery(InstallCustomerRelation::class) {
                where(table.projectId eq projectId)
                where(table.installId eq installId)
                where(table.customerId eq customerId)
                select(table)
            }.limit(1).execute().firstOrNull()

    /** 软删该 install 当前其它 customer 的有效关系（换绑：一 install 只绑一 customer）。返回受影响行数。 */
    fun softDeleteOtherActiveByInstall(mc: ModuleCtx, projectId: String, installId: UUID, keepCustomerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.customerId ne keepCustomerId)
            where(table.deletedAt.isNull())
        }.execute()

    /** 软删指定有效关系（logout 解绑）。返回受影响行数。 */
    fun softDeleteActive(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.customerId eq customerId)
            where(table.deletedAt.isNull())
        }.execute()

    /** 软删该 customer 的全部有效关系（deleteAccount）。返回受影响行数。 */
    fun softDeleteAllActiveByCustomer(mc: ModuleCtx, projectId: String, customerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.deletedAt.isNull())
        }.execute()

    /**
     * 复活软删行（re-bind）：deleted_at 置回 null。
     * 需 IGNORED 行为，否则默认过滤会挡住「更新已软删行」。
     */
    fun reactivate(mc: ModuleCtx, id: UUID): Int =
        mc.sql.filters { setBehavior(InstallCustomerRelation::class, LogicalDeletedBehavior.IGNORED) }
            .createUpdate(InstallCustomerRelation::class) {
                set(table.deletedAt, null)
                set(table.updatedAt, Instant.now())
                where(table.id eq id)
            }.execute()
}
