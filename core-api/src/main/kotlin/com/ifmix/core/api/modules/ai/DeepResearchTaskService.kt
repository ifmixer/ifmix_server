package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.dto.notification.NotiType
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.modules.notification.NotificationFacade
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.Executor

/**
 * DeepResearch 后台任务协调（设计 §3.3 / §6.1，PG premium_result 存储版）。
 *
 * - mutation 事务提交后由 Fetcher 调 [submit]，任务跑在 Spring 管理的虚拟线程 executor 上，
 *   脱离 HTTP 请求线程/请求事务/请求 ActionContext，仅依赖 [DeepResearchTaskContext] 快照；
 * - AI 调用在事务外；DB 阶段经 [TxRunner] 开短事务走 writer（Handler 不自开事务）；
 * - 失败路径（AI 调用异常）一律 CAS 20→40，CAS 未命中即放弃；
 * - 成功路径：单短事务内 CAS 20→30 + 写 premium_result → scan 行 FOR UPDATE 锁内按
 *   (created_at,id) 比较回写 scan_record AI 字段 + 权威指针 + 配额（设计 §3.4）。
 */
@Service
class DeepResearchTaskService(
    private val mcFactory: ModuleCtxFactory,
    private val txRunner: TxRunner,
    private val scanRunner: ScanRunner,
    private val scanAggHandler: ScanAggHandler,
    private val notificationFacade: NotificationFacade,
    @Qualifier("deepResearchExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 事务提交后调用：提交后台任务。任何异常不得外泄到调用方（mutation 已返回）。 */
    fun submit(ctx: DeepResearchTaskContext) {
        executor.execute { runTask(ctx) }
    }

    private fun runTask(ctx: DeepResearchTaskContext) {
        try {
            doRun(ctx)
        } catch (t: Throwable) {
            // 兜底：任何未预期异常不允许杀死 executor 线程；尽力把任务置 FAILED（CAS 未命中则放弃）。
            // 用 INTERNAL_ERROR 而非 AI_FAILED——漏到此处的多是成功回写阶段的 DB/锁错误，AI 其实已成功。
            log.error("DeepResearch task crashed. deepResearchId={}", ctx.deepResearchId, t)
            runCatching {
                val mc = mcFactory.forProject(offlineCtx(ctx))
                txRunner.withTx(mc) {
                    scanAggHandler.casDeepResearchFailed(
                        it, ctx.deepResearchId, AiTaskErrorCodes.INTERNAL_ERROR,
                        mapOf("message" to safeSummary(t)),
                    )
                }
            }
        }
    }

    private fun doRun(ctx: DeepResearchTaskContext) {
        val actionCtx = offlineCtx(ctx)
        // preferReader=false（isMutation=true）→ writer；globalTxSql=null（不复用请求级 globalTx）
        val mc = mcFactory.forProject(actionCtx)

        // 1. AI 调用（事务外）
        val aiResponse = try {
            scanRunner.run(actionCtx, scanAggHandler.buildDeepResearchScanInput(ctx))
        } catch (e: Exception) {
            log.warn("DeepResearch AI call failed. deepResearchId={}, scanRecordId={}", ctx.deepResearchId, ctx.scanRecordId, e)
            fail(mc, ctx, AiTaskErrorCodes.AI_FAILED, mapOf("message" to safeSummary(e)))
            return
        }
        val result = scanAggHandler.toDeepResearchResult(ctx, aiResponse)

        // AI 正常返回即任务 SUCCESS；业务 scan_status 原样保存在 basicResult，前端从权威 ScanRecord 读取。
        // 2. 成功回写（单短事务：CAS 20→30 + premium_result → FOR UPDATE 锁内指针比较 → scan_record 回写 + 配额）
        val finalized = txRunner.withTx(mc) {
            scanAggHandler.finalizeDeepResearchSuccess(it, ctx, result)
        }
        log.info(
            "DeepResearch task finished. deepResearchId={}, scanRecordId={}, isLatest={}",
            ctx.deepResearchId, ctx.scanRecordId, finalized,
        )
        if (ctx.deepResearchPushEnabled) {
            dispatchSuccessNotification(ctx, result, finalized)
        }
    }

    private fun fail(mc: com.ifmix.core.api.infra.db.ModuleCtx, ctx: DeepResearchTaskContext, errorCode: String, details: Map<String, Any?>?) {
        log.warn("DeepResearch task failed. deepResearchId={}, errorCode={}", ctx.deepResearchId, errorCode)
        txRunner.withTx(mc) {
            scanAggHandler.casDeepResearchFailed(it, ctx.deepResearchId, errorCode, details)
        }
    }

    internal fun dispatchSuccessNotification(
        ctx: DeepResearchTaskContext,
        result: DeepResearchResult,
        finalized: Boolean,
    ) {
        if (!finalized || !ctx.deepResearchPushEnabled) return
        val installId = ctx.installId ?: run {
            log.debug("Skip DeepResearch notification: no installId. deepResearchId={}", ctx.deepResearchId)
            return
        }
        val content = scanAggHandler.buildDeepResearchNotificationContent(ctx, result)
        runCatching {
            notificationFacade.sendToInstall(
                NotificationRequest(
                    projectId = ctx.projectId,
                    installId = installId,
                    content = content,
                    notiType = NotiType.DEEP_RESEARCH,
                )
            )
        }.onFailure {
            log.warn("DeepResearch push failed. deepResearchId={}", ctx.deepResearchId, it)
        }
    }

    /** 脱离请求的 ActionContext：isMutation=true → preferReader=false（走 writer），不复用请求级 globalTx。 */
    private fun offlineCtx(ctx: DeepResearchTaskContext): ActionContext =
        ActionContext(
            projectId = ctx.projectId,
            actorId = ctx.customerId,
            actorType = ActorTypes.CUSTOMER,
            anonymous = false,
            locale = ctx.locale,
            country = ctx.country,
            currency = ctx.currency,
            isMutation = true,
        )

    /** 对外安全摘要（不含异常栈），截断防超大 error_details。 */
    private fun safeSummary(t: Throwable): String = (t.message ?: t.toString()).take(300)
}
