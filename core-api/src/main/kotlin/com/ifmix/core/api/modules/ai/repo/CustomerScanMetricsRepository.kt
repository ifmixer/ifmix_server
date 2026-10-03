package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.CustomerScanMetrics
import com.ifmix.core.api.entity.ai.customerId
import com.ifmix.core.api.entity.ai.deepResearchCount
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.pendingScanCount
import com.ifmix.core.api.entity.ai.scanCount
import com.ifmix.core.api.entity.ai.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.sql
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * core_ai_customer_scan_metrics：每 customer 一行的扫描累计计数。行不存在 = 计数均为 0。
 * 写入统一「先懒建行（INSERT ... ON CONFLICT DO NOTHING）再单条 UPDATE」，
 * 「检查 + 自增」仍在单条 UPDATE 内完成，无并发窗口。
 */
@Repository
class CustomerScanMetricsRepository {


    /** 创建 scan 时预留一次额度；scan_count + pending_scan_count 共同占用 quota。 */
    fun reserveScan(mc: ModuleCtx, projectId: String, customerId: UUID, limit: Int): Int {
        ensureRow(mc, projectId, customerId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(sql(Boolean::class, "%e + %e < %v") {
                expression(table.scanCount)
                expression(table.pendingScanCount)
                value(limit)
            })
            set(table.pendingScanCount, sql(Int::class, "%e + 1") { expression(table.pendingScanCount) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    /** AI 正常返回时把一个 pending reservation 转成已完成 scan。 */
    fun completeScan(mc: ModuleCtx, projectId: String, customerId: UUID): Int {
        ensureRow(mc, projectId, customerId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(sql(Boolean::class, "%e > 0") { expression(table.pendingScanCount) })
            set(table.pendingScanCount, sql(Int::class, "%e - 1") { expression(table.pendingScanCount) })
            set(table.scanCount, sql(Int::class, "%e + 1") { expression(table.scanCount) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    /** 技术失败、超时或提交失败时释放一个 pending reservation。 */
    fun releaseScan(mc: ModuleCtx, projectId: String, customerId: UUID): Int {
        ensureRow(mc, projectId, customerId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(sql(Boolean::class, "%e > 0") { expression(table.pendingScanCount) })
            set(table.pendingScanCount, sql(Int::class, "%e - 1") { expression(table.pendingScanCount) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }
    /**
     * 成功扫描 +1，带终身上限的原子拒绝：仅当 scan_count < limit 时自增。
     * 返回 1=成功计入，0=已达上限（调用方据此拒绝）。
     */
    fun tryIncrementScanCount(mc: ModuleCtx, projectId: String, customerId: UUID, limit: Int): Int {
        ensureRow(mc, projectId, customerId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(sql(Boolean::class, "%e < %v") { expression(table.scanCount); value(limit) })
            set(table.scanCount, sql(Int::class, "%e + 1") { expression(table.scanCount) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    /** 成功深度研究 +1，带终身上限的原子拒绝。返回 1=成功，0=已达上限。 */
    fun tryIncrementDeepResearchCount(mc: ModuleCtx, projectId: String, customerId: UUID, limit: Int): Int {
        ensureRow(mc, projectId, customerId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(sql(Boolean::class, "%e < %v") { expression(table.deepResearchCount); value(limit) })
            set(table.deepResearchCount, sql(Int::class, "%e + 1") { expression(table.deepResearchCount) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    /** 读当前 (scanCount, deepResearchCount)；无行返回 null（调用方按 0 处理）。 */
    fun findCounts(mc: ModuleCtx, projectId: String, customerId: UUID): Pair<Int, Int>? =
        mc.sql.createQuery(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            select(table.scanCount, table.deepResearchCount)
        }.limit(1).execute().firstOrNull()?.let { it._1 to it._2 }

    /**
     * 合并加总：把 cur 的两项计数加到 target（登录合并时调用）。
     * 终身累计语义下必须加总，否则登录后额度被重置、限流可绕过。
     */
    fun addCounts(mc: ModuleCtx, projectId: String, targetId: UUID, addScan: Int, addDeepResearch: Int): Int {
        ensureRow(mc, projectId, targetId)
        return mc.sql.createUpdate(CustomerScanMetrics::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq targetId)
            set(table.scanCount, sql(Int::class, "%e + %v") { expression(table.scanCount); value(addScan) })
            set(table.deepResearchCount, sql(Int::class, "%e + %v") { expression(table.deepResearchCount); value(addDeepResearch) })
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    /**
     * 懒建计数行：已存在则什么都不做（并发安全）。
     * 注意 Jimmer 在 id 已赋值时按 id 判重（不看 @Key），所以 id 必须是确定值 = customerId；
     * 用随机 id 会绕过判重直接 INSERT，撞 customer_id 唯一索引。
     */
    private fun ensureRow(mc: ModuleCtx, projectId: String, customerId: UUID) {
        val now = Instant.now()
        mc.sql.save(
            CustomerScanMetrics {
                this.id = customerId // id 即 customerId：确定性主键，INSERT_IF_ABSENT 按 id 判重才能幂等
                this.projectId = projectId
                this.customerId = customerId
                this.scanCount = 0
                this.pendingScanCount = 0
                this.deepResearchCount = 0
                this.createdAt = now
                this.updatedAt = now
            },
            SaveMode.INSERT_IF_ABSENT,
        )
    }
}
