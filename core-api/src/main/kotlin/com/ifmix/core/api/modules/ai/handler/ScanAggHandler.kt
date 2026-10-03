package com.ifmix.core.api.modules.ai.handler

import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.RunDeepResearchInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanStatusSnapshot
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.dto.notification.NotiType
import org.slf4j.LoggerFactory
import com.ifmix.core.api.dto.ai.ScanMediaItem
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanStatuses
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ImageCategories
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.modules.ai.ScanRunner
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ScanAggHandler(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
    private val deepResearchRepo: com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository,
    private val scanPrompt: com.ifmix.core.api.modules.ai.service.ScanPrompt,
    private val scanMetricsRepo: com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository,
    private val scanQuota: com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 创建事务内：清理 stale 任务、预留额度并写入 IN_PROGRESS scan。 */
    fun createScanTask(sc: ModuleCtx, input: NewScanInput): ScanTaskContext {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        val installId = sc.action.mustGetTokenInstallId()
        val now = Instant.now()
        cleanupStaleScans(sc, projectId, customerId, now.minusSeconds(STALE_IN_PROGRESS_SEC))
        if (scanMetricsRepo.reserveScan(sc, projectId, customerId, scanQuota.scan) != 1) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted",
            )
        }

        val scanId = UuidV7.generate()
        val images = input.images.map {
            ScanTaskContext.ImageRefItem(it.imageKey, it.category ?: ImageCategories.MAIN, it.mediaType)
        }
        val record = ScanRecord {
            id = scanId
            this.projectId = projectId
            this.images = images.map { ImageRef(it.imageKey, it.category ?: ImageCategories.MAIN) }
            this.basicResult = null
            this.latestDeepResearchId = null
            this.status = ScanStatuses.IN_PROGRESS
            this.errorCode = null
            this.errorDetails = null
            this.clientIp = sc.action.clientIp
            this.customerId = customerId
            this.installId = installId
            this.locale = sc.action.locale
            this.country = sc.action.country
            this.currency = sc.action.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = input.collected ?: false
            this.isPublic = true
            this.hasDeepSearch = false
            this.promptVersion = scanPrompt.promptVersion
            this.createdAt = now
            this.updatedAt = now
        }
        if (!scanRepo.insert(sc, record)) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.INTERNAL, "failed to create scan record",
            )
        }
        return ScanTaskContext(
            projectId = projectId,
            customerId = customerId,
            installId = installId,
            scanId = scanId,
            locale = sc.action.locale,
            country = sc.action.country,
            currency = sc.action.currency,
            images = images,
            collected = input.collected ?: false,
            promptVersion = scanPrompt.promptVersion,
            createdAt = now,
        )
    }

    /** 后台任务使用的 AI 输入构造，不依赖请求线程上下文。 */
    fun buildScanInput(ctx: ScanTaskContext): ScanInput = ScanInput(
        scanId = ctx.scanId,
        items = ctx.images.map {
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", it.imageKey),
                mediaType = guessMediaType(it.imageKey, it.mediaType),
            )
        },
        locale = ctx.locale,
        country = ctx.country,
        currency = ctx.currency,
    )

    /** AI 正常返回即任务成功，AI 业务 status 原样存入 basicResult。 */
    fun finalizeScanSuccess(sc: ModuleCtx, ctx: ScanTaskContext, basicResult: Map<String, Any?>?): Boolean {
        if (scanRepo.casSuccess(sc, ctx.projectId, ctx.customerId, ctx.scanId, basicResult) != 1) return false
        if (scanMetricsRepo.completeScan(sc, ctx.projectId, ctx.customerId) != 1) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.INTERNAL,
                "scan reservation ledger is inconsistent: scanId=${ctx.scanId}",
            )
        }
        return true
    }

    /** 技术失败终结任务；CAS 未赢时绝不释放其它路径的 reservation。 */
    fun casScanFailed(
        sc: ModuleCtx,
        ctx: ScanTaskContext,
        errorCode: String,
        errorDetails: Map<String, Any?>?,
    ): Boolean {
        if (scanRepo.casFailed(sc, ctx.projectId, ctx.customerId, ctx.scanId, errorCode, errorDetails) != 1) return false
        if (scanMetricsRepo.releaseScan(sc, ctx.projectId, ctx.customerId) != 1) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.INTERNAL,
                "scan reservation ledger is inconsistent: scanId=${ctx.scanId}",
            )
        }
        return true
    }

    /** 查询端惰性超时：CAS 赢得终态后才释放 reservation，再读取最新状态。 */
    fun getScanStatus(sc: ModuleCtx, scanId: UUID): ScanStatusSnapshot {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        var record = scanRepo.findByIdOwned(sc, projectId, customerId, scanId)
            ?: throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        if (record.status == ScanStatuses.IN_PROGRESS && record.updatedAt.isBefore(Instant.now().minusSeconds(STALE_IN_PROGRESS_SEC))) {
            val ctx = ScanTaskContext(
                projectId = projectId,
                customerId = customerId,
                installId = record.installId,
                scanId = scanId,
                locale = record.locale,
                country = record.country,
                currency = record.currency,
                images = record.images.map { ScanTaskContext.ImageRefItem(it.key, it.category, null) },
                collected = record.collected,
                promptVersion = record.promptVersion,
                createdAt = record.createdAt,
            )
            casScanFailed(sc, ctx, AiTaskErrorCodes.TIMEOUT, null)
            record = scanRepo.findByIdOwned(sc, projectId, customerId, scanId) ?: record
        }
        return ScanStatusSnapshot(record.id, record.status, record.errorCode)
    }

    fun buildScanNotificationRequest(
        ctx: ScanTaskContext,
        basicResult: Map<String, Any?>?,
    ): NotificationRequest? {
        val installId = ctx.installId ?: return null
        return NotificationRequest(
            projectId = ctx.projectId,
            installId = installId,
            notiType = NotiType.SCAN_RESULT,
            content = buildScanNotificationContent(ctx, basicResult),
        )
    }

    private fun cleanupStaleScans(sc: ModuleCtx, projectId: String, customerId: UUID, cutoff: Instant) {
        scanRepo.findStaleInProgress(sc, projectId, customerId, cutoff).forEach { staleId ->
            val record = scanRepo.findByIdOwned(sc, projectId, customerId, staleId) ?: return@forEach
            val staleCtx = ScanTaskContext(
                projectId = projectId,
                customerId = customerId,
                installId = record.installId,
                scanId = staleId,
                locale = record.locale,
                country = record.country,
                currency = record.currency,
                images = record.images.map { ScanTaskContext.ImageRefItem(it.key, it.category, null) },
                collected = record.collected,
                promptVersion = record.promptVersion,
                createdAt = record.createdAt,
            )
            casScanFailed(sc, staleCtx, AiTaskErrorCodes.TIMEOUT, null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildScanNotificationContent(ctx: ScanTaskContext, basicResult: Map<String, Any?>?): NotificationContent {
        val status = ((basicResult?.get("scan_status") as? Map<String, Any?>)?.get("status") as? String)
            ?.trim()?.uppercase()
        val name = ((basicResult?.get("object_overview") as? Map<String, Any?>)?.get("name") as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val advice = status == "INSUFFICIENT_IMAGE" || status == "NON_PHYSICAL_SUBJECT"
        val locale = ctx.locale.orEmpty().lowercase()
        val (title, body) = when {
            locale.startsWith("zh") && advice -> "扫描已完成" to "点击查看拍摄建议"
            locale.startsWith("zh") -> "扫描已完成" to (name?.let { "“$it”扫描已完成，点击查看结果" } ?: "点击查看扫描结果")
            locale.startsWith("ja") && advice -> "スキャンが完了しました" to "撮影のアドバイスを確認してください"
            locale.startsWith("ja") -> "スキャンが完了しました" to (name?.let { "$it の結果を確認できます" } ?: "結果を確認できます")
            advice -> "Scan complete" to "Tap to view photo guidance"
            else -> "Scan complete" to (name?.let { "Your scan of $it is ready" } ?: "Your scan result is ready")
        }
        val imageUrl = ctx.images.firstOrNull { it.category == ImageCategories.MAIN }?.let {
            runCatching { objectStorage.getPublicUrl("ugc", it.imageKey) }
                .onFailure { error -> log.warn("Scan notification image URL unavailable. scanId={}", ctx.scanId, error) }
                .getOrNull()
        }
        return NotificationContent(
            title = title,
            body = body,
            imageUrl = imageUrl,
            link = "/p/${ctx.projectId}/scan-result/${ctx.scanId}",
        )
    }
    @Suppress("UNCHECKED_CAST")
    fun buildDeepResearchNotificationContent(
        ctx: DeepResearchTaskContext,
        result: DeepResearchResult,
    ): NotificationContent {
        val status = result.status
        val name = ((result.basicResult?.get("object_overview") as? Map<String, Any?>)?.get("name") as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val advice = status == "INSUFFICIENT_IMAGE" || status == "NON_PHYSICAL_SUBJECT"
        val locale = ctx.locale.orEmpty().lowercase()
        val (title, body) = when {
            locale.startsWith("zh") && advice -> "深度研究已完成" to "点击查看拍摄建议"
            locale.startsWith("zh") -> "深度研究已完成" to (name?.let { "“$it”深度研究已完成，点击查看报告" } ?: "点击查看深度研究报告")
            locale.startsWith("ja") && advice -> "深度研究が完了しました" to "撮影のアドバイスを確認してください"
            locale.startsWith("ja") -> "深度研究が完了しました" to (name?.let { "$it のレポートを確認できます" } ?: "レポートを確認できます")
            advice -> "Deep research complete" to "Tap to view photo guidance"
            else -> "Deep research complete" to (name?.let { "Your deep research of $it is ready" } ?: "Your deep research report is ready")
        }
        val imageUrl = ctx.images.firstOrNull { it.category == ImageCategories.MAIN }?.let {
            runCatching { objectStorage.getPublicUrl("ugc", it.key) }
                .onFailure { error -> log.warn("DeepResearch notification image URL unavailable. deepResearchId={}", ctx.deepResearchId, error) }
                .getOrNull()
        }
        return NotificationContent(
            title = title,
            body = body,
            imageUrl = imageUrl,
            link = "/p/${ctx.projectId}/scan-result/${ctx.scanRecordId}",
        )
    }


    /** 外部 AI 调用（无事务）— 解析 images、运行 AI、返回结果 DTO */
    fun runAiScan(mc: ModuleCtx, input: NewScanInput): AiScanResult {
        val actionCtx = mc.action
        // 前置配额校验（AI 调用前拒绝，省下 AI 成本）；并发兜底见 saveNewScan 的原子自增。
        val actorId = actionCtx.mustGetActorId()
        val used = scanMetricsRepo.findCounts(mc, actionCtx.mustGetProjectId(), actorId)?.first ?: 0
        if (used >= scanQuota.scan) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"
        )
        val scanId = UuidV7.generate()
        val now = Instant.now()

        val resolved = input.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", img.imageKey),
                mediaType = guessMediaType(img.imageKey, img.mediaType),
            )
        }

        val scanInput = ScanInput(
            scanId=scanId,
            items = resolved,
            locale = actionCtx.locale,
            country = actionCtx.country,
            currency = actionCtx.currency,
        )
        val aiResponse = scanRunner.run(actionCtx, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse

        return AiScanResult(
            scanId = scanId,
            projectId = actionCtx.mustGetProjectId(),
            locale = actionCtx.locale,
            country = actionCtx.country,
            currency = actionCtx.currency,
            clientIp = actionCtx.clientIp,
            images = input.images,
            basicResult = basicResult,
            collected = input.collected ?: false,
            promptVersion = scanPrompt.promptVersion,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 在事务内将 AiScanResult 持久化为 ScanRecord */
    /** 事务内持久化新 scan，返回 scanId。调用方（事务外）用 id 重查全字段 ScanRecord，避免返回半构造对象。 */
    fun saveNewScan(sc: ModuleCtx, result: AiScanResult): UUID {
        val record = ScanRecord {
            id = result.scanId
            this.projectId = result.projectId
            this.images = result.images.map { ImageRef(key = it.imageKey, category = it.category ?: ImageCategories.MAIN) }
            this.basicResult = result.basicResult
            this.errorCode = null
            this.errorDetails = null
            this.status = com.ifmix.core.api.entity.ai.ScanStatuses.READY
            this.clientIp = result.clientIp
            this.customerId = sc.action.actorId
            this.installId = sc.action.mustGetTokenInstallId()
            this.locale = result.locale
            this.country = result.country
            this.currency = result.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = result.collected
            this.isPublic = true
            this.hasDeepSearch = false
            this.latestDeepResearchId = null
            this.promptVersion = result.promptVersion
            this.createdAt = result.createdAt
            this.updatedAt = result.updatedAt
        }
        scanRepo.save(sc, record)
        // 原子自增并兜底并发：仅当 scan_count < limit 时 +1；达上限则拒绝（整个事务回滚）。
        val actorId = sc.action.mustGetActorId()
        if (scanMetricsRepo.tryIncrementScanCount(sc, sc.action.mustGetProjectId(), actorId, scanQuota.scan) == 0) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"
            )
        }
        return result.scanId
    }

    fun updateScan(sc: ModuleCtx, input: UpdateScanInput): Boolean {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        // owner-scoped：非本人拥有（或不存在）统一 NOT_FOUND；存在则执行更新（no-op set 也算成功）。
        if (!scanRepo.existsOwned(sc, projectId, customerId, input.id)) {
            throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        }
        scanRepo.partialUpdate(sc, projectId, customerId, input.id, input)
        return true
    }

    /**
     * 批量更新 scan（owner-scoped）：仅影响调用者本人拥有的记录，返回实际更新数。
     * ids 为空视为非法请求。
     */
    fun batchUpdateScan(sc: ModuleCtx, input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): Int {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        if (input.ids.isEmpty()) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "ids cannot be empty"
        )
        return scanRepo.batchPartialUpdate(
            sc, projectId, customerId, input.ids,
            collected = input.set.collected,
            isPublic = input.set.isPublic,
        )
    }

    fun deleteScan(sc: ModuleCtx, id: UUID): Boolean {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        // owner-scoped：非本人拥有（或不存在）统一 NOT_FOUND。
        if (!scanRepo.deleteByIdOwned(sc, projectId, customerId, id)) {
            throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        }
        return true
    }

    fun findById(sc: ModuleCtx, id: UUID): ScanRecord? =
        scanRepo.findByIdOwned(sc, sc.action.mustGetProjectId(), sc.action.mustGetActorId(), id)

    // ==================== DeepResearch 异步任务（设计 docs/superpowers/specs/2026-10-02） ====================

    companion object {
        /** 惰性超时：IN_PROGRESS 且 updatedAt 早于该秒数 → 查询侧 CAS 置 FAILED(TIMEOUT)（设计决策 8，5 min）。 */
        private const val STALE_IN_PROGRESS_SEC = 300L
    }

    /**
     * DeepResearch 前置（事务内）：owner-scoped 校验归属并整体替换 images。
     * 即使随后的 AI 调用失败，图片也已提交。images 顺序即数组顺序；category 为图片分类。
     */
    fun updateDeepResearchImages(sc: ModuleCtx, input: RunDeepResearchInput) {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        val imageRefs = input.images.map { ImageRef(key = it.imageKey, category = it.category ?: ImageCategories.MAIN) }
        val updated = scanRepo.updateImages(sc, projectId, customerId, input.scanRecordId, imageRefs)
        if (updated == 0) throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
    }

    /**
     * DeepResearch 第一步（mutation 事务内，设计 §3.3 步骤 1-3）：
     * 1) owner-scoped 校验归属并整体替换 images；
     * 2) 配额预检（used >= limit → QUOTA_EXCEEDED 回滚，不创建任务）；
     * 3) 创建 IN_PROGRESS 记录（status=20, premium_result/basic_result=null，成功回写时填）。
     * 成功才扣配额（创建时不扣，无退款）；返回不可变上下文，事务提交后由 Fetcher 交
     * [com.ifmix.core.api.modules.ai.DeepResearchTaskService] 执行。
     */
    fun createDeepResearchTask(sc: ModuleCtx, input: RunDeepResearchInput): DeepResearchTaskContext {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        updateDeepResearchImages(sc, input)
        val used = scanMetricsRepo.findCounts(sc, projectId, customerId)?.second ?: 0
        if (used >= scanQuota.deepResearch) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "deep research quota exhausted"
            )
        }
        val scan = scanRepo.findByIdOwned(sc, projectId, customerId, input.scanRecordId)
            ?: throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)

        val id = UuidV7.generate()
        val now = Instant.now()
        deepResearchRepo.insert(
            sc,
            ScanDeepResearch {
                this.id = id
                this.projectId = projectId
                this.scanRecordId = input.scanRecordId
                this.premiumResult = null
                this.promptVersion = scanPrompt.promptVersion
                this.status = DeepResearchStatuses.IN_PROGRESS
                this.errorCode = null
                this.errorDetails = null
                this.createdAt = now
                this.updatedAt = now
            },
        )
        return DeepResearchTaskContext(
            projectId = projectId,
            customerId = customerId,
            deepResearchId = id,
            scanRecordId = input.scanRecordId,
            images = scan.images.map { DeepResearchTaskContext.ImageRefItem(it.key, it.category) },
            locale = scan.locale,
            country = scan.country,
            currency = scan.currency,
            promptVersion = scanPrompt.promptVersion,
            createdAt = now,
            installId = sc.action.tokenInstallId,
        )
    }

    /** DeepResearch 第二步（后台、事务外）：组装 deep-research 的 ScanInput（脱离请求上下文）。 */
    fun buildDeepResearchScanInput(ctx: DeepResearchTaskContext): ScanInput =
        ScanInput(
            scanId = ctx.scanRecordId,
            items = ctx.images.map {
                ScanMediaItem(
                    imageUrl = objectStorage.getPublicUrl("ugc", it.key),
                    mediaType = guessMediaType(it.key, null),
                )
            },
            locale = ctx.locale,
            country = ctx.country,
            currency = ctx.currency,
            type = com.ifmix.core.api.dto.ai.ScanType.DEEP_RESEARCH,
        )

    /** AI 响应 Map → DeepResearchResult（沿用 basic_result/premium_result 提取规则）。 */
    fun toDeepResearchResult(ctx: DeepResearchTaskContext, aiResponse: Map<String, Any?>): DeepResearchResult {
        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse
        @Suppress("UNCHECKED_CAST")
        val premiumResult = aiResponse["premium_result"] as? Map<String, Any?>
        return DeepResearchResult(
            scanRecordId = ctx.scanRecordId,
            projectId = ctx.projectId,
            basicResult = basicResult,
            premiumResult = premiumResult,
            promptVersion = ctx.promptVersion,
        )
    }

    /** 终态 CAS 包装（事务内调用）：false = 已被其它路径终结（查询惰性超时抢先等），调用方放弃。 */
    fun casDeepResearchFailed(
        sc: ModuleCtx,
        deepResearchId: UUID,
        errorCode: String,
        errorDetails: Map<String, Any?>?,
    ): Boolean = deepResearchRepo.casFailed(sc, deepResearchId, errorCode, errorDetails) == 1

    /**
     * 成功回写（事务内调用，设计 §3.4 单短事务）：
     * 1) CAS 20→30 + 写 premium_result（JSONB 入 PG）；affected=0 → 已被其它路径终结，返回 false（不扣配额）；
     * 2) scan 行 FOR UPDATE 锁内按 (created_at,id) 比较，较新 → 同语句回写 scan_record AI 字段 +
     *    latest_deep_research_id，配额 +1（封顶）；旧任务晚完成 → 仅存历史，不动 scan_record、不扣配额。
     */
    fun finalizeDeepResearchSuccess(
        sc: ModuleCtx,
        ctx: DeepResearchTaskContext,
        result: DeepResearchResult,
    ): Boolean {
        if (deepResearchRepo.casSuccess(sc, ctx.deepResearchId, result.premiumResult, result.basicResult) != 1) return false
        // 返回值语义 = isLatest（本次成功是否成为权威 latest）；旧任务晚完成 → false（仅存历史）
        val pointerMoved = scanRepo.updateAiFieldsAndPointerIfNewer(
            sc, ctx.projectId, ctx.customerId, ctx.scanRecordId,
            ctx.deepResearchId, ctx.createdAt, result.basicResult, result.promptVersion,
        )
        if (pointerMoved) {
            // 配额跟随「成为 latest 的那次成功」：封顶自增，超额不卡已完成结果（设计决策 4）
            scanMetricsRepo.tryIncrementDeepResearchCount(sc, ctx.projectId, ctx.customerId, scanQuota.deepResearch)
        }
        return pointerMoved
    }

    /**
     * 轮询状态查询（owner-scoped），含惰性超时判定（设计决策 12）：
     * IN_PROGRESS 且 updatedAt 超 10 min → CAS 置 FAILED(TIMEOUT)；CAS 未命中说明后台刚终结 → 重读最新状态。
     */
    fun getDeepResearchStatus(sc: ModuleCtx, deepResearchId: UUID): ScanDeepResearch {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        val dr = deepResearchRepo.findById(sc, projectId, deepResearchId)
            ?: throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        // owner-scope：父 scan 必须属于当前 customer
        if (!scanRepo.existsOwned(sc, projectId, customerId, dr.scanRecordId)) {
            throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        }
        if (dr.status != DeepResearchStatuses.IN_PROGRESS) return dr
        val staleCutoff = Instant.now().minusSeconds(STALE_IN_PROGRESS_SEC)
        if (!dr.updatedAt.isBefore(staleCutoff)) return dr
        casDeepResearchFailed(sc, deepResearchId, AiTaskErrorCodes.TIMEOUT, null)
        return deepResearchRepo.findById(sc, projectId, deepResearchId) ?: dr
    }

    /** 批量按 deepResearchId 查询（latestDeepResearch DataLoader 用；owner 由父 ScanRecord 保证）。 */
    fun findDeepResearchByIds(sc: ModuleCtx, ids: Collection<UUID>): List<ScanDeepResearch> =
        deepResearchRepo.findByIds(sc, sc.action.mustGetProjectId(), ids)

    fun presignedUploadUrl(sc: ModuleCtx, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(sc: ModuleCtx, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(sc: ModuleCtx, objectKey: String): String =
        objectStorage.getPublicUrl("ugc", objectKey)

    fun findMyScans(sc: ModuleCtx, findOptions: CommonFindOptions?): Page<ScanRecord> {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        return scanRepo.findMyScans(sc, projectId, customerId, findOptions)
    }

    private fun guessMediaType(key: String, mediaType: String?): String =
        mediaType ?: run {
            val ext = key.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic" -> "image/heic"
                else -> "application/octet-stream"
            }
        }

}
