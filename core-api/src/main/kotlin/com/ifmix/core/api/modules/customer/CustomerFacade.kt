package com.ifmix.core.api.modules.customer

import com.ifmix.core.api.entity.customer.Customer
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.customer.repo.CustomerRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CustomerFacade(
    private val mcFactory: ModuleCtxFactory,
    private val customerRepo: CustomerRepository,
) {
    fun findById(ctx: ActionContext, id: UUID): Customer? =
        customerRepo.findById(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    fun createCustomer(ctx: ActionContext): UUID =
        customerRepo.createCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId())

    fun exists(ctx: ActionContext, id: UUID): Boolean =
        customerRepo.exists(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    /**
     * 成功扫描 +1，带终身上限的原子拒绝。复用调用方事务上下文（与扫描写入同事务）。
     * 返回 true=已计入；false=已达上限（调用方应拒绝本次操作）。
     */
    fun tryIncrementScanCount(mc: ModuleCtx, id: UUID, limit: Int): Boolean =
        customerRepo.tryIncrementScanCount(mc, mc.action.mustGetProjectId(), id, limit) > 0

    /** 成功深度研究 +1，带终身上限的原子拒绝。返回 true=已计入；false=已达上限。 */
    fun tryIncrementDeepResearchCount(mc: ModuleCtx, id: UUID, limit: Int): Boolean =
        customerRepo.tryIncrementDeepResearchCount(mc, mc.action.mustGetProjectId(), id, limit) > 0

    /** 读当前 (scanCount, deepResearchCount)；不存在返回 null。 */
    fun findCounts(mc: ModuleCtx, id: UUID): Pair<Int, Int>? =
        customerRepo.findCounts(mc, mc.action.mustGetProjectId(), id)

    /** 读当前计数（非事务，AI 调用前置校验用）。 */
    fun findCounts(ctx: ActionContext, id: UUID): Pair<Int, Int>? =
        customerRepo.findCounts(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    /** 合并加总：把 cur 的两项计数加到 target（登录合并时调用）。 */
    fun addCounts(mc: ModuleCtx, targetId: UUID, addScan: Int, addDeepResearch: Int): Int =
        customerRepo.addCounts(mc, mc.action.mustGetProjectId(), targetId, addScan, addDeepResearch)
}
