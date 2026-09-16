package com.ifmix.core.api.modules.customer.repo

import com.ifmix.core.api.entity.customer.Customer
import com.ifmix.core.api.entity.customer.anonymous
import com.ifmix.core.api.entity.customer.projectId
import com.ifmix.core.api.entity.customer.authIdentityId
import com.ifmix.core.api.entity.customer.id
import com.ifmix.core.api.entity.customer.mergedTo
import com.ifmix.core.api.entity.customer.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.gt
import org.babyfish.jimmer.sql.kt.ast.expression.isNotNull
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class CustomerRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(Customer::class, UUID::class) }

    fun createCustomer(mc: ModuleCtx, projectId: String): UUID {
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = Customer {
            this.id = id
            this.projectId = projectId
            this.anonymous = true
            this.mergedTo = null
            this.createdAt = now
            this.updatedAt = now
        }
        mc.sql.entities.save(entity)
        return id
    }

    /** 转正：匿名 → 非匿名（登录且无需迁移时）。 */
    fun promote(mc: ModuleCtx, projectId: String, id: UUID): Int =
        mc.sql.createUpdate(Customer::class) {
            where(table.projectId eq projectId)
            where(table.id eq id)
            set(table.anonymous, false)
            set(table.updatedAt, Instant.now())
        }.execute()

    /** 绑定账号：设置 customer.authIdentityId（登录转正时）。 */
    fun setAuthIdentity(mc: ModuleCtx, projectId: String, id: UUID, authIdentityId: UUID): Int =
        mc.sql.createUpdate(Customer::class) {
            where(table.projectId eq projectId)
            where(table.id eq id)
            set(table.authIdentityId, authIdentityId)
            set(table.updatedAt, Instant.now())
        }.execute()

    /** 合并 tombstone：将 cur 标记为已并入 existing（方向硬编码 匿名 cur → existing，R1）。 */
    fun markMerged(mc: ModuleCtx, projectId: String, curId: UUID, existingId: UUID): Int =
        mc.sql.createUpdate(Customer::class) {
            where(table.projectId eq projectId)
            where(table.id eq curId)
            set(table.mergedTo, existingId)
            set(table.updatedAt, Instant.now())
        }.execute()

    fun save(mc: ModuleCtx, entity: Customer) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, projectId: String, id: UUID) = tpl.findById(mc, projectId, id)

    /** 反查：app 内绑定该 authIdentity 的存活 customer（排除已合并 tombstone）。 */
    fun findByAuthIdentity(mc: ModuleCtx, projectId: String, authIdentityId: UUID): UUID? =
        mc.sql.createQuery(Customer::class) {
            where(table.projectId eq projectId)
            where(table.authIdentityId eq authIdentityId)
            where(table.mergedTo.isNull())
            select(table.id)
        }.limit(1).execute().firstOrNull()

    fun findByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>) = tpl.findByIds(mc, projectId, ids)
    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.deleteById(mc, projectId, id)
    fun exists(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.exists(mc, projectId, id)

    // ===== 阶段 6：匿名清理 =====

    /**
     * 未合并匿名僵尸候选：anonymous=true AND merged_to IS NULL。
     * 「无有效 refresh token」的判定放在调用方（scheduler）逐个校验——
     * refresh token 属 auth 模块，不在此跨模块 join（AGENTS：跨模块用逻辑外键 UUID）。
     * 按 id 升序分批，返回一批 id（幂等可重入：删掉后下批自然前移）。
     */
    fun findAnonymousZombieCandidates(mc: ModuleCtx, projectId: String, afterId: UUID?, limit: Int): List<UUID> =
        mc.sql.createQuery(Customer::class) {
            where(table.projectId eq projectId)
            where(table.anonymous eq true)
            where(table.mergedTo.isNull())
            afterId?.let { where(table.id gt it) }
            orderBy(table.id.asc())
            select(table.id)
        }.limit(limit).execute()

    /**
     * 已合并 tombstone 候选：merged_to IS NOT NULL AND updatedAt < cutoff（超审计窗口）。
     * 合并事务已把资源/token 全部迁走，tombstone 是空壳，可安全删。
     */
    fun findMergedTombstoneCandidates(mc: ModuleCtx, projectId: String, cutoff: Instant, afterId: UUID?, limit: Int): List<UUID> =
        mc.sql.createQuery(Customer::class) {
            where(table.projectId eq projectId)
            where(table.mergedTo.isNotNull())
            where(table.updatedAt lt cutoff)
            afterId?.let { where(table.id gt it) }
            orderBy(table.id.asc())
            select(table.id)
        }.limit(limit).execute()

    /**
     * 物理删除 customer（绕过软删——customer 无 @LogicalDeleted，此处显式 PHYSICAL 以示意图）。
     * 仅按 id 集合删，调用方须已确保这些 id 是匿名僵尸或 tombstone。
     */
    fun physicalDeleteByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return mc.sql.createDelete(Customer::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.projectId eq projectId)
            where(table.id valueIn ids)
        }.execute()
    }
}
