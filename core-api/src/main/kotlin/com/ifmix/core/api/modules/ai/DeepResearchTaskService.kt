package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.DeepResearchDocs
import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.entity.ai.DeepResearchErrorCodes
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Executor

/**
 * DeepResearch 后台任务协调（设计 §3.3 / §8.1）。
 *
 * - mutation 事务提交后由 Fetcher 调 [submit]，任务跑在 Spring 管理的虚拟线程 executor 上，
 *   脱离 HTTP 请求线程/请求事务/请求 ActionContext，仅依赖 [DeepResearchTaskContext] 快照；
 * - AI 调用与 R2 上传在事务外；DB 阶段经 [TxRunner] 开短事务走 writer（Handler 不自开事务）；
 * - 失败路径（AI 失败 / scan_status 拒绝 / R2 最终失败）一律 CAS 20→40（设计 §3.4），CAS 未命中即放弃；
 * - 成功路径：CAS 20→30 + file_key → (created_at,id) 较新者同事务回写 scan_record AI 字段 + 权威指针 + 配额。
 */
@Service
class DeepResearchTaskService(
    private val mcFactory: ModuleCtxFactory,
    private val txRunner: TxRunner,
    private val scanRunner: ScanRunner,
    private val scanAggHandler: ScanAggHandler,
    private val objectStorage: ObjectStorage,
    private val snakeCaseMapper: ObjectMapper,
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
            // 兜底：任何未预期异常不允许杀死 executor 线程；尽力把任务置 FAILED（CAS 未命中则放弃）
            log.error("DeepResearch task crashed. deepResearchId={}", ctx.deepResearchId, t)
            runCatching {
                val mc = mcFactory.forProject(offlineCtx(ctx))
                txRunner.withTx(mc) {
                    scanAggHandler.casDeepResearchFailed(
                        it, ctx.deepResearchId, DeepResearchErrorCodes.AI_FAILED,
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
            fail(mc, ctx, DeepResearchErrorCodes.AI_FAILED, mapOf("message" to safeSummary(e)))
            return
        }
        val result = scanAggHandler.toDeepResearchResult(ctx, aiResponse)
        if (!result.isSuccess) {
            // scan_status 非 SUCCESS/PARTIAL：error_details 带 scan_status 供前端提示补拍
            fail(mc, ctx, DeepResearchErrorCodes.AI_STATUS_REJECTED, mapOf("scan_status" to result.scanStatus))
            return
        }

        // 2. doc 组装（scanRecordSnapshot = DB 现值 + AI 回写后语义；设计 §5）
        val doc = buildDoc(ctx, mc, result)

        // 3. R2 上传（事务外，固定间隔重试；AI 成本高，不因 R2 抖动丢结果）
        val fileKey = DeepResearchDocs.objectKey(ctx.projectId, ctx.deepResearchId, ctx.createdAt)
        val bytes = snakeCaseMapper.writeValueAsBytes(doc)
        try {
            uploadWithRetry(fileKey, bytes)
        } catch (e: Exception) {
            log.error("DeepResearch R2 upload failed after retries. deepResearchId={}, fileKey={}", ctx.deepResearchId, fileKey, e)
            fail(mc, ctx, DeepResearchErrorCodes.R2_UPLOAD_FAILED, mapOf("message" to safeSummary(e)))
            return
        }

        // 4. 成功回写（单事务：CAS 20→30 + file_key → (created_at,id) 较新者回写 scan_record + 配额）
        val finalized = txRunner.withTx(mc) {
            scanAggHandler.finalizeDeepResearchSuccess(it, ctx, result, fileKey)
        }
        log.info(
            "DeepResearch task finished. deepResearchId={}, scanRecordId={}, isLatest={}",
            ctx.deepResearchId, ctx.scanRecordId, finalized,
        )
    }

    /** doc 的 scanRecordSnapshot：DB 现值 + AI 回写后语义（basicResult/hasDeepSearch 用本次结果覆盖）。 */
    private fun buildDoc(
        ctx: DeepResearchTaskContext,
        mc: com.ifmix.core.api.infra.db.ModuleCtx,
        result: DeepResearchResult,
    ): Map<String, Any?> {
        val scan = scanAggHandler.findScanForSnapshot(mc, ctx.scanRecordId)
        val scanSnapshot: Map<String, Any?> = if (scan == null) {
            emptyMap()
        } else {
            linkedMapOf(
                "id" to scan.id.toString(),
                "status" to scan.status,
                "locale" to scan.locale,
                "country" to scan.country,
                "currency" to scan.currency,
                "images" to scan.images.map { linkedMapOf("key" to it.key, "category" to it.category) },
                "basicResult" to result.basicResult,
                "hasDeepSearch" to true,
                "userDisplayName" to scan.userDisplayName,
                "userNotes" to scan.userNotes,
                "collected" to scan.collected,
                "isPublic" to scan.isPublic,
                "createdAt" to scan.createdAt.toString(),
                "updatedAt" to scan.updatedAt?.toString(),
            )
        }
        val drSnapshot = linkedMapOf(
            "id" to ctx.deepResearchId.toString(),
            "scanRecordId" to ctx.scanRecordId.toString(),
            "status" to DeepResearchStatuses.SUCCESS,
            "promptVersion" to ctx.promptVersion,
            "docVersion" to ctx.docVersion,
        )
        return DeepResearchDocs.buildDoc(ctx.docVersion, ctx.promptVersion, scanSnapshot, drSnapshot, result.premiumResult)
    }

    /** R2 上传，固定间隔重试（设计决策 2：2-3 次）。全部失败抛出，由调用方 CAS 置 FAILED。 */
    private fun uploadWithRetry(fileKey: String, bytes: ByteArray) {
        var lastError: Exception? = null
        repeat(UPLOAD_ATTEMPTS) { attempt ->
            try {
                objectStorage.upload(DeepResearchDocs.BUCKET_ID, fileKey, bytes, "application/json")
                return
            } catch (e: Exception) {
                lastError = e
                log.warn("R2 upload failed (attempt {}/{}): {}", attempt + 1, UPLOAD_ATTEMPTS, e.toString())
                if (attempt < UPLOAD_ATTEMPTS - 1) Thread.sleep(UPLOAD_RETRY_INTERVAL_MS)
            }
        }
        throw lastError ?: IllegalStateException("R2 upload failed")
    }

    private fun fail(mc: com.ifmix.core.api.infra.db.ModuleCtx, ctx: DeepResearchTaskContext, errorCode: String, details: Map<String, Any?>?) {
        log.warn("DeepResearch task failed. deepResearchId={}, errorCode={}", ctx.deepResearchId, errorCode)
        txRunner.withTx(mc) {
            scanAggHandler.casDeepResearchFailed(it, ctx.deepResearchId, errorCode, details)
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

    companion object {
        private val log = LoggerFactory.getLogger(DeepResearchTaskService::class.java)
        private const val UPLOAD_ATTEMPTS = 3
        private const val UPLOAD_RETRY_INTERVAL_MS = 1_000L
    }
}
