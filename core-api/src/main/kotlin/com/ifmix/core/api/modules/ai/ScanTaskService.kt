package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.notification.NotificationFacade
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.Executor

/** Scan 异步任务协调：AI 在事务外，状态/配额回写在单短事务内。 */
@Service
class ScanTaskService(
    private val mcFactory: ModuleCtxFactory,
    private val txRunner: TxRunner,
    private val scanRunner: ScanRunner,
    private val scanAggHandler: ScanAggHandler,
    private val notificationFacade: NotificationFacade,
    @Qualifier("scanExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** mutation 事务提交后调用；executor 拒绝由调用方处理为 TASK_SUBMISSION_FAILED。 */
    fun submit(ctx: ScanTaskContext) {
        executor.execute { runTask(ctx) }
    }

    private fun runTask(ctx: ScanTaskContext) {
        try {
            doRun(ctx)
        } catch (t: Throwable) {
            log.error("Scan task crashed. projectId={}, scanId={}", ctx.projectId, ctx.scanId, t)
            runCatching {
                fail(ctx, AiTaskErrorCodes.INTERNAL_ERROR, mapOf("message" to safeSummary(t)))
            }
        }
    }

    private fun doRun(ctx: ScanTaskContext) {
        val actionCtx = offlineCtx(ctx)
        val mc = mcFactory.forProject(actionCtx)
        val aiResponse = try {
            scanRunner.run(actionCtx, scanAggHandler.buildScanInput(ctx))
        } catch (e: Exception) {
            log.warn("Scan AI call failed. projectId={}, scanId={}", ctx.projectId, ctx.scanId, e)
            fail(ctx, AiTaskErrorCodes.AI_FAILED, mapOf("message" to safeSummary(e)))
            return
        }
        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse
        val finalized = txRunner.withTx(mc) {
            scanAggHandler.finalizeScanSuccess(it, ctx, basicResult)
        }
        if (!finalized) return

        // push 失败不能影响已提交的 SUCCESS；通知 facade 自身也会吞掉 channel 异常。
        runCatching {
            scanAggHandler.buildScanNotificationRequest(ctx, basicResult)?.let(notificationFacade::sendToInstall)
        }.onFailure {
            log.warn("Scan notification dispatch failed. projectId={}, scanId={}", ctx.projectId, ctx.scanId, it)
        }
    }

    private fun fail(ctx: ScanTaskContext, errorCode: String, details: Map<String, Any?>?) {
        val mc = mcFactory.forProject(offlineCtx(ctx))
        txRunner.withTx(mc) {
            scanAggHandler.casScanFailed(it, ctx, errorCode, details)
        }
    }

    private fun offlineCtx(ctx: ScanTaskContext): ActionContext = ActionContext(
        projectId = ctx.projectId,
        actorId = ctx.customerId,
        actorType = ActorTypes.CUSTOMER,
        anonymous = false,
        locale = ctx.locale,
        country = ctx.country,
        currency = ctx.currency,
        isMutation = true,
    )

    private fun safeSummary(t: Throwable): String = (t.message ?: t.toString()).take(300)
}
