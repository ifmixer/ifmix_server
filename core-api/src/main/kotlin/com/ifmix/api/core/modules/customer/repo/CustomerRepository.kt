package com.ifmix.api.core.modules.customer.repo

import com.ifmix.api.core.entity.user.Customer
import com.ifmix.api.core.entity.user.anonymous
import com.ifmix.api.core.entity.user.appId
import com.ifmix.api.core.entity.user.id
import com.ifmix.api.core.entity.user.mergedTo
import com.ifmix.api.core.entity.user.updatedAt
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
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
    companion object { private val tpl = AppCrudRepoTemplate(Customer::class) }

    fun createCustomer(mc: ModuleCtx, appId: UUID): UUID {
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = Customer {
            this.id = id
            this.appId = appId
            this.anonymous = true
            this.mergedTo = null
            this.metadata = null
            this.createdAt = now
            this.updatedAt = now
        }
        mc.sql.entities.save(entity)
        return id
    }

    /** 转正：匿名 → 非匿名（登录且无需迁移时）。 */
    fun promote(mc: ModuleCtx, appId: UUID, id: UUID): Int =
        mc.sql.createUpdate(Customer::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            set(table.anonymous, false)
            set(table.updatedAt, Instant.now())
        }.execute()

    /** 合并 tombstone：将 cur 标记为已并入 existing（方向硬编码 匿名 cur → existing，R1）。 */
    fun markMerged(mc: ModuleCtx, appId: UUID, curId: UUID, existingId: UUID): Int =
        mc.sql.createUpdate(Customer::class) {
            where(table.appId eq appId)
            where(table.id eq curId)
            set(table.mergedTo, existingId)
            set(table.updatedAt, Instant.now())
        }.execute()

    fun save(mc: ModuleCtx, entity: Customer) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)

    // ===== 阶段 6：匿名清理 =====

    /**
     * 未合并匿名僵尸候选：anonymous=true AND merged_to IS NULL。
     * 「无有效 refresh token」的判定放在调用方（scheduler）逐个校验——
     * refresh token 属 auth 模块，不在此跨模块 join（AGENTS：跨模块用逻辑外键 UUID）。
     * 按 id 升序分批，返回一批 id（幂等可重入：删掉后下批自然前移）。
     */
    fun findAnonymousZombieCandidates(mc: ModuleCtx, appId: UUID, afterId: UUID?, limit: Int): List<UUID> =
        mc.sql.createQuery(Customer::class) {
            where(table.appId eq appId)
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
    fun findMergedTombstoneCandidates(mc: ModuleCtx, appId: UUID, cutoff: Instant, afterId: UUID?, limit: Int): List<UUID> =
        mc.sql.createQuery(Customer::class) {
            where(table.appId eq appId)
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
    fun physicalDeleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return mc.sql.createDelete(Customer::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.appId eq appId)
            where(table.id valueIn ids)
        }.execute()
    }
}
