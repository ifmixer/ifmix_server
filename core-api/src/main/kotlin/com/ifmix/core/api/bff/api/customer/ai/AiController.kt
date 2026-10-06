package com.ifmix.core.api.bff.api.customer.ai

import com.ifmix.core.api.dto.ai.AddCollectionItemInput
import com.ifmix.core.api.dto.ai.AddCollectionItemRes
import com.ifmix.core.api.dto.ai.BatchUpdateScanInput
import com.ifmix.core.api.dto.ai.BatchUpdateScanRes
import com.ifmix.core.api.dto.ai.CreateScanRes
import com.ifmix.core.api.dto.ai.DeepResearchStatusRes
import com.ifmix.core.api.dto.ai.DeleteScanRes
import com.ifmix.core.api.dto.ai.FindScanByIdInput
import com.ifmix.core.api.dto.ai.GetDeepResearchStatusInput
import com.ifmix.core.api.dto.ai.GetScanStatusInput
import com.ifmix.core.api.dto.ai.ListCollectionItemsInput
import com.ifmix.core.api.dto.ai.RemoveCollectionItemsInput
import com.ifmix.core.api.dto.ai.RemoveCollectionItemsRes
import com.ifmix.core.api.dto.ai.RunDeepResearchInput
import com.ifmix.core.api.dto.ai.RunDeepResearchRes
import com.ifmix.core.api.dto.ai.ScanCollectionItemRes
import com.ifmix.core.api.dto.ai.ScanCollectionRes
import com.ifmix.core.api.dto.ai.ScanListInput
import com.ifmix.core.api.dto.ai.ScanRecordListRes
import com.ifmix.core.api.dto.ai.ScanRecordRes
import com.ifmix.core.api.dto.ai.ScanStatusRes
import com.ifmix.core.api.dto.ai.UpdateScanInput
import com.ifmix.core.api.dto.ai.UpdateScanRes
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanStatuses
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.DeepResearchTaskService
import com.ifmix.core.api.modules.ai.ScanTaskService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ai 模块 API controller（rollout §4 M3）：13 个 `POST /api/customer/core/{actionName}`，
 * 与 rpc-rollout-client.md §1 R3 行一一对应（旧 GraphQL 字段名见 companion 常量注释 / rpc-rollout-client.md §1 R3）。
 *
 * 请求/响应信封、ctx 构造与事务边界全部照 DemoController 模式；语义红线（rollout §4 M3）：
 * - createScan / runDeepResearch：「事务内建任务 → 事务外 AI/executor → 失败 CAS 终态」拆分
 *   与 AiFetcher 完全一致（AI 调用在 Handler 层，本迁移不触碰）；
 * - updateScan 写后读从 writer（事务内 findById，同 AiFetcher 的 txCtx 读）；
 * - 配额/限流错误码 429000/429001/503000 逐一保留（retryAfterSec 必带 → Retry-After 头自动生效）；
 * - scan / deep-research 的 install 层限流（attest 规格 §4.6）原样移植（见 [rateLimitByAction]）。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "AI API", description = "ai 模块 RPC（scan / deepResearch / collection，M3 去 GraphQL 化）")
class AiController(
    private val ctxFactory: ActionContextFactory,
    private val aiService: AiFacade,
    private val collectionService: com.ifmix.core.api.modules.ai.ScanCollectionFacade,
    private val deepResearchTaskService: DeepResearchTaskService,
    private val scanTaskService: ScanTaskService,
    private val queryService: AiQueryService,
    private val globalTx: GlobalTxRunner,
    private val rateLimiter: RateLimiter,
    private val rlProps: RateLimitProperties,
) {

    companion object {
        // ai 模块 13 个 RPC action 的 actionName 常量（原 AiSpecs 机械搬移；全部 CUSTOMER +
        // requireProjectId=true，与 AiFetcher.fromDfe 的 requireAppId=true / ACTOR_CUSTOMER 对齐）。
        const val SCAN_GET_BY_ID = "q_ai_scan_getById"
        const val SCAN_LIST = "q_ai_scan_list"
        const val SCAN_GET_STATUS = "q_ai_scan_getStatus"
        const val DEEP_RESEARCH_RUN = "m_ai_deepResearch_run"
        const val DEEP_RESEARCH_GET_STATUS = "q_ai_deepResearch_getStatus"
        const val COLLECTION_GET_DEFAULT = "q_ai_collection_getDefault"
        const val COLLECTION_ITEM_ADD = "m_ai_collectionItem_add"
        const val COLLECTION_ITEM_REMOVE_MANY = "m_ai_collectionItem_removeMany"
        const val COLLECTION_ITEM_LIST = "q_ai_collectionItem_list"
        const val SCAN_CREATE_ONE = "m_ai_scan_createOne"
        const val SCAN_UPDATE_ONE = "m_ai_scan_updateOne"
        const val SCAN_DELETE_ONE = "m_ai_scan_deleteOne"
        const val SCAN_UPDATE_MANY = "m_ai_scan_updateMany"
    }

    // ==================== Scan queries ====================

    @Operation(operationId = SCAN_GET_BY_ID)
    @PostMapping(SCAN_GET_BY_ID, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findScanById(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindScanByIdInput>): ResponseEntity<Envelope<ScanRecordRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_GET_BY_ID, isMutation = false, body = body)
        val input = body.requireInput()
        val record = queryService.findScanById(ctx, input.id) ?: throw ApiError(ErrorCode.NOT_FOUND)
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, record))
    }

    @Operation(operationId = SCAN_LIST)
    @PostMapping(SCAN_LIST, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findScans(request: HttpServletRequest, @RequestBody body: ApiRequestBody<ScanListInput>): ResponseEntity<Envelope<Page<ScanRecordListRes>>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_LIST, isMutation = false, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, queryService.findScans(ctx, input.findOptions)))
    }

    @Operation(operationId = SCAN_GET_STATUS)
    @PostMapping(SCAN_GET_STATUS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getScanStatus(request: HttpServletRequest, @RequestBody body: ApiRequestBody<GetScanStatusInput>): ResponseEntity<Envelope<ScanStatusRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_GET_STATUS, isMutation = false, body = body)
        val input = body.requireInput()
        // 惰性超时判定可能产生 CAS 写（IN_PROGRESS → FAILED(TIMEOUT)），走事务（同 AiFetcher）
        val res = globalTx.withTx(ctx) { txCtx -> queryService.getScanStatus(txCtx, input.scanId) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res))
    }

    // ==================== DeepResearch ====================

    @Operation(operationId = DEEP_RESEARCH_RUN)
    @PostMapping(DEEP_RESEARCH_RUN, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun runDeepResearch(request: HttpServletRequest, @RequestBody body: ApiRequestBody<RunDeepResearchInput>): ResponseEntity<Envelope<RunDeepResearchRes>> {
        val ctx = ctxFactory.fromRpc(request, DEEP_RESEARCH_RUN, isMutation = true, body = body)
        val input = body.requireInput()
        rateLimitByAction(ctx, "deep-research", DownstreamLimits.of(rlProps.deepResearch), "too many runDeepResearch")
        // 异步化（设计 §3.3）：事务内完成 images 更新 + 配额预检 + 创建 IN_PROGRESS 记录，
        // 事务提交后（withTx 返回即已提交）才提交后台任务——后台才能读到已提交的记录。
        val taskCtx = globalTx.withTx(ctx) { txCtx -> aiService.createDeepResearchTask(txCtx, input) }
        // executor 提交失败（进程关闭/资源拒绝，设计 §9）：记录已 IN_PROGRESS → CAS 置 FAILED 并返回终态。
        var status = DeepResearchStatuses.IN_PROGRESS
        var errorCode: String? = null
        try {
            deepResearchTaskService.submit(taskCtx)
        } catch (e: Exception) {
            val casWon = globalTx.withTx(ctx) { txCtx ->
                aiService.casDeepResearchFailed(txCtx, taskCtx.deepResearchId, AiTaskErrorCodes.TASK_SUBMISSION_FAILED, null)
            }
            if (casWon) {
                status = DeepResearchStatuses.FAILED
                errorCode = AiTaskErrorCodes.TASK_SUBMISSION_FAILED
            }
        }
        return ResponseEntity.ok(
            Envelope.ok(ctx.requestId, RunDeepResearchRes(deepResearchId = taskCtx.deepResearchId, status = status, errorCode = errorCode)),
        )
    }

    @Operation(operationId = DEEP_RESEARCH_GET_STATUS)
    @PostMapping(DEEP_RESEARCH_GET_STATUS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getDeepResearchStatus(request: HttpServletRequest, @RequestBody body: ApiRequestBody<GetDeepResearchStatusInput>): ResponseEntity<Envelope<DeepResearchStatusRes>> {
        val ctx = ctxFactory.fromRpc(request, DEEP_RESEARCH_GET_STATUS, isMutation = false, body = body)
        val input = body.requireInput()
        // 惰性超时判定可能产生 CAS 写（IN_PROGRESS → FAILED(TIMEOUT)），走事务（同 AiFetcher）
        val res = globalTx.withTx(ctx) { txCtx -> queryService.getDeepResearchStatus(txCtx, input.deepResearchId) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res))
    }

    // ==================== Collection ====================

    @Operation(operationId = COLLECTION_GET_DEFAULT)
    @PostMapping(COLLECTION_GET_DEFAULT, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getDefaultCollection(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<ScanCollectionRes>> {
        val ctx = ctxFactory.fromRpc(request, COLLECTION_GET_DEFAULT, isMutation = false, body = body)
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, queryService.getDefaultCollection(ctx)))
    }

    @Operation(operationId = COLLECTION_ITEM_ADD)
    @PostMapping(COLLECTION_ITEM_ADD, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun addCollectionItem(request: HttpServletRequest, @RequestBody body: ApiRequestBody<AddCollectionItemInput>): ResponseEntity<Envelope<AddCollectionItemRes>> {
        val ctx = ctxFactory.fromRpc(request, COLLECTION_ITEM_ADD, isMutation = true, body = body)
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.addItem(txCtx, com.ifmix.core.api.dto.ai.AddItemReq(scanRecordId = input.scanRecordId))
            // 语义保持：旧 GraphQL AddScanCollectionItemResult.collectionId 实际承载的是 item id（历史行为，勿「修正」）
            AddCollectionItemRes(collectionId = result.id, alreadyExists = false)
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res))
    }

    @Operation(operationId = COLLECTION_ITEM_REMOVE_MANY)
    @PostMapping(COLLECTION_ITEM_REMOVE_MANY, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun removeCollectionItems(request: HttpServletRequest, @RequestBody body: ApiRequestBody<RemoveCollectionItemsInput>): ResponseEntity<Envelope<RemoveCollectionItemsRes>> {
        val ctx = ctxFactory.fromRpc(request, COLLECTION_ITEM_REMOVE_MANY, isMutation = true, body = body)
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.removeItems(txCtx, com.ifmix.core.api.dto.ai.RemoveItemsReq(scanRecordIds = input.scanRecordIds))
            RemoveCollectionItemsRes(removedCount = result.removed)
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res))
    }

    @Operation(operationId = COLLECTION_ITEM_LIST)
    @PostMapping(COLLECTION_ITEM_LIST, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findCollectionItems(request: HttpServletRequest, @RequestBody body: ApiRequestBody<ListCollectionItemsInput>): ResponseEntity<Envelope<Page<ScanCollectionItemRes>>> {
        val ctx = ctxFactory.fromRpc(request, COLLECTION_ITEM_LIST, isMutation = false, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, queryService.findCollectionItems(ctx, input)))
    }

    // ==================== Scan mutations ====================

    @Operation(operationId = SCAN_CREATE_ONE)
    @PostMapping(SCAN_CREATE_ONE, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createScan(request: HttpServletRequest, @RequestBody body: ApiRequestBody<com.ifmix.core.api.dto.ai.NewScanInput>): ResponseEntity<Envelope<CreateScanRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_CREATE_ONE, isMutation = true, body = body)
        val input = body.requireInput()
        rateLimitByAction(ctx, "scan", DownstreamLimits.of(rlProps.scan), "too many createScan")
        val taskCtx = globalTx.withTx(ctx) { txCtx -> aiService.createScanTask(txCtx, input) }
        var status = ScanStatuses.IN_PROGRESS
        var errorCode: String? = null
        try {
            scanTaskService.submit(taskCtx)
        } catch (e: Exception) {
            val casWon = globalTx.withTx(ctx) { txCtx ->
                aiService.casScanFailed(txCtx, taskCtx, AiTaskErrorCodes.TASK_SUBMISSION_FAILED, null)
            }
            if (casWon) {
                status = ScanStatuses.FAILED
                errorCode = AiTaskErrorCodes.TASK_SUBMISSION_FAILED
            }
        }
        return ResponseEntity.ok(
            Envelope.ok(ctx.requestId, CreateScanRes(scanId = taskCtx.scanId, status = status, errorCode = errorCode)),
        )
    }

    @Operation(operationId = SCAN_UPDATE_ONE)
    @PostMapping(SCAN_UPDATE_ONE, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateScan(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateScanInput>): ResponseEntity<Envelope<UpdateScanRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_UPDATE_ONE, isMutation = true, body = body)
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            val success = aiService.updateScan(txCtx, input)
            // 写后读从 writer：详情在写事务内读（同 AiFetcher 的 txCtx 读），固定 DTO 不再看 selectionSet
            val record = if (success) aiService.findById(txCtx, input.id) else null
            UpdateScanRes(success = success, scanRecord = record?.let { queryService.toDetailRes(txCtx, it) })
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res))
    }

    @Operation(operationId = SCAN_DELETE_ONE)
    @PostMapping(SCAN_DELETE_ONE, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteScan(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindScanByIdInput>): ResponseEntity<Envelope<DeleteScanRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_DELETE_ONE, isMutation = true, body = body)
        val input = body.requireInput()
        val success = globalTx.withTx(ctx) { txCtx -> aiService.deleteScan(txCtx, input.id) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, DeleteScanRes(success = success)))
    }

    @Operation(operationId = SCAN_UPDATE_MANY)
    @PostMapping(SCAN_UPDATE_MANY, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun batchUpdateScan(request: HttpServletRequest, @RequestBody body: ApiRequestBody<BatchUpdateScanInput>): ResponseEntity<Envelope<BatchUpdateScanRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_UPDATE_MANY, isMutation = true, body = body)
        val input = body.requireInput()
        val updated = globalTx.withTx(ctx) { txCtx -> aiService.batchUpdateScan(txCtx, input) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, BatchUpdateScanRes(updatedCount = updated)))
    }

    // ==================== 限流（attest 规格 §4.6，自 AiFetcher 原样移植） ====================

    /**
     * 下游接口的 install 层限流（attest 规格 §4.6「下游接口的 install 层」）：
     * - 有可信 [ActionContext.tokenInstallId]（只认 token 签名过的 iid；scan/DR 用 customer token 的 iid）：
     *   install 层（scan/DR：5/min + 100/天）→ IP 层（100/min + 1000/天）。install 层拒绝不碰 IP 计数器；
     *   IP 层拒绝时 install 额度已扣、**不退**（被拒请求一律不退款）。
     * - 无 iid（legacy fallback 兼容期，旧 app 只带可伪造的 x-install-id）：走 legacy IP 层
     *   （独立计数器，key 带 `legacy:` 段：scan 5/min+500/天、DR 3/min+300/天），不占新大额 IP 计数器。
     *   legacy fallback 关闭后 `tokenInstallId==null` 的请求在业务前被拒
     *   （`mustGetTokenInstallId` 现有语义 401000，此处不重复）。
     * 顺序 install 层 → IP 层 → 业务；按 projectId 隔离。
     */
    /** scan / deep-research 的限流阈值（RateLimitProperties 的 Scan / DeepResearch 字段一致，取公共六项）。 */
    private data class DownstreamLimits(
        val legacyIpMinute: Int,
        val legacyIpDay: Int,
        val ipMinute: Int,
        val ipDay: Int,
        val installMinute: Int,
        val installDay: Int,
    ) {
        companion object {
            fun of(s: RateLimitProperties.Scan) = DownstreamLimits(s.legacyIpMinute, s.legacyIpDay, s.ipMinute, s.ipDay, s.installMinute, s.installDay)
            fun of(d: RateLimitProperties.DeepResearch) = DownstreamLimits(d.legacyIpMinute, d.legacyIpDay, d.ipMinute, d.ipDay, d.installMinute, d.installDay)
        }
    }

    private fun rateLimitByAction(
        ctx: ActionContext,
        action: String,
        limits: DownstreamLimits,
        message: String,
    ) {
        val projectId = ctx.mustGetProjectId()
        val ip = ctx.clientIp ?: "unknown"
        val iid = ctx.tokenInstallId
        when {
            iid != null -> {
                // install 层（防滥用，额度小）：先查 install 再查 IP（§4.6「被拒不退」语义）
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:install:min:$iid", limits.installMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:install:day:$iid", limits.installDay, message)
                // IP 层（系统防护，额度大）
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:ip:min:$ip", limits.ipMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:ip:day:$ip", limits.ipDay, message)
            }
            else -> {
                // legacy（无可信 iid，兼容期旧客户端）：严格阈值独立计数器，不占新大额 IP 计数器
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:legacy:ip:min:$ip", limits.legacyIpMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:legacy:ip:day:$ip", limits.legacyIpDay, message)
            }
        }
    }

    private fun checkRateLimit(window: Window, key: String, limit: Int, message: String) {
        when (val rl = rateLimiter.check(window, key, limit)) {
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, message, retryAfterSec = rl.retryAfterSec)
            else -> Unit
        }
    }
}
