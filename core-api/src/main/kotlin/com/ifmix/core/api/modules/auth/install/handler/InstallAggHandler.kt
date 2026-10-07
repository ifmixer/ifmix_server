package com.ifmix.core.api.modules.auth.install.handler

import com.ifmix.core.api.entity.auth.install.AttestationStatuses
import com.ifmix.core.api.entity.auth.install.AttestationVerifyStatuses
import com.ifmix.core.api.entity.auth.install.Install
import com.ifmix.core.api.entity.auth.install.InstallAttestation
import com.ifmix.core.api.entity.auth.install.InstallCustomerRelation
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.attest.AttestGuard.AttestationOutcome
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.auth.install.InstallNotificationTarget
import com.ifmix.core.api.modules.auth.install.repo.InstallAttestationRepository
import com.ifmix.core.api.modules.auth.install.repo.InstallCustomerRelationRepository
import com.ifmix.core.api.modules.auth.install.repo.InstallRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class InstallAggHandler(
    private val installRepo: InstallRepository,
    private val relationRepo: InstallCustomerRelationRepository,
    private val attestRepo: InstallAttestationRepository,
    private val jwt: AuthJwtService,
) {

    data class CreateInstallRes(val installId: UUID, val installToken: String)

    /** attestExisting 结果（§6.7 第 8 步，事务内权威判定）：新建一行 / 幂等（已有 ACTIVE 绑定）。 */
    sealed interface AttestExistingRes {
        data object Created : AttestExistingRes
        data object Idempotent : AttestExistingRes
    }

    /**
     * createInstall（规格 §5.3 事务内绑定，须在事务内调用——外层 Fetcher 包 globalTx）。
     * - [outcome] = null：无 proof（bundle==null，含客户端声明 UNAVAILABLE）或 attest 全局关闭 → 只建 install。
     * - 带 proof 的请求必留一行（verify_status 码表 AttestationVerifyStatuses）：
     *   Bound → iOS VALID（subject 非 null）：按 (projectId, 110, keyId) 查已有 attestation——
     *   已存在 → 403001(key_reused) **不建 install**（与 mode 无关，§5.3）；
     *   不存在 → 建 install（platform 由 provider 派生=20，§4.2）+ attestation 行
     *   （verify_status=10、status=10、sign_count=0、attestation_object 原文、signals/evidence 固定键）。
     *   Failed → verify_status=20、status=NOT_BOUND，evidence.reason 记失败原因（不存 attestation_object）。
     *   NotEvaluated → verify_status=30、status=NOT_BOUND。
     * - [storeType]：入参 null → NULL（§5.9 缺省）；∉{10,20} → 400000。VALID 且 storeType≠10
     *   （provider 110）→ 由 Fetcher 调 Guard.logStoreMismatch 打日志，不拒绝。
     */
    fun createInstallWithProof(
        mc: ModuleCtx,
        deviceInfo: Map<String, Any?>?,
        storeType: Int?,
        outcome: com.ifmix.core.api.infra.attest.AttestGuard.AttestationOutcome?,
    ): CreateInstallRes {
        val projectId = mc.projectId!!
        if (storeType != null && storeType != 10 && storeType != 20) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "storeType must be 10 (APP_STORE) or 20 (GOOGLE_PLAY)")
        }
        val attestation = (outcome as? AttestationOutcome.Bound)?.proof
        val keyId = attestation?.subject

        if (keyId != null) {
            // iOS VALID：先查已有绑定；已存在 → key_reused（不建 install）
            val existing = attestRepo.findBySubject(mc, projectId, attestation!!.provider, keyId)
            if (existing != null) {
                throw ApiError(
                    ErrorCode.ATTESTATION_FAILED,
                    "attestation key already bound to another install (key_reused)",
                )
            }
        }

        val installId = UuidV7.generate()
        val now = Instant.now()
        val platform = when {
            attestation != null -> when (attestation.provider) {
                110 -> 20 // §4.2：VALID 时 platform 由 provider 派生（110=APP_ATTEST → 20=IOS）
                else -> 10
            }
            else -> platformInt(mc.action.clientPlatform) // 非 VALID 沿用现状（header）
        }
        installRepo.save(mc, Install {
            this.id = installId
            this.projectId = projectId
            this.platform = platform
            this.deviceInfo = deviceInfo
            this.appVersion = mc.action.appVersion
            this.otaVersion = mc.action.otaVersion
            this.locale = mc.action.locale
            this.country = mc.action.country
            this.currency = mc.action.currency
            this.regIp = mc.action.clientIp
            this.storeType = storeType
            this.firebaseInstallId = null
            this.fcmToken = null
            this.scanResultNotiEnabled = true
            this.fcmTokenValid = true
            this.deepResearchNotiEnabled = true
            this.createdAt = now
            this.updatedAt = now
        })

        if (outcome != null) {
            saveAttestationRow(mc, projectId, installId, outcome)
        }
        val token = jwt.signInstall(installId.toString(), projectId)
        return CreateInstallRes(installId = installId, installToken = token)
    }

    /**
     * attestation 行（带 proof 必留痕）：Bound → status=ACTIVE、sign_count=0、attestation_object 原文、
     * signals/evidence 固定键（§5.3/§5.4/§5.7）；Failed/NotEvaluated → status=NOT_BOUND 留痕行，
     * evidence.reason 记原因，不存 attestation_object（垃圾证明原文不入库）。
     */
    private fun saveAttestationRow(
        mc: ModuleCtx,
        projectId: String,
        installId: UUID,
        outcome: com.ifmix.core.api.infra.attest.AttestGuard.AttestationOutcome,
    ) {
        val proof = (outcome as? AttestationOutcome.Bound)?.proof
        val verifyStatus = when (outcome) {
            is AttestationOutcome.Bound -> AttestationVerifyStatuses.VALID
            is AttestationOutcome.Failed -> AttestationVerifyStatuses.INVALID
            is AttestationOutcome.NotEvaluated -> AttestationVerifyStatuses.NOT_EVALUATED
        }
        val now = Instant.now()
        val saved = attestRepo.insert(mc, InstallAttestation {
            this.id = UuidV7.generate()
            this.projectId = projectId
            this.installId = installId
            this.provider = outcome.provider
            this.subject = outcome.subject
            this.publicKey = proof?.publicKey
            this.attestationObject = outcome.rawObject
            this.challenge = outcome.challenge
            this.signCount = 0
            this.receipt = null
            this.receiptExpiresAt = null
            this.nextRefreshAt = null
            this.refreshFailureCount = 0
            this.fraudMetric = null
            this.signals = proof?.signals ?: mapOf("provider" to outcome.provider)
            this.evidence = when (outcome) {
                is AttestationOutcome.Bound -> proof?.evidence
                is AttestationOutcome.Failed -> mapOf("reason" to outcome.reason.code)
                is AttestationOutcome.NotEvaluated -> mapOf("reason" to "not_evaluated")
            }
            this.status = if (proof != null) AttestationStatuses.ACTIVE else AttestationStatuses.NOT_BOUND
            this.verifyStatus = verifyStatus
            this.lastUsedAt = null
            this.createdAt = now
            this.updatedAt = now
        })
        if (!saved) throw ApiError(ErrorCode.INTERNAL, "attestation row insert failed")
    }

    /**
     * iOS 找回（§3.3，事务内步骤 d/e，须在事务内调用——外层 Fetcher 包 globalTx）。
     * 预查（无绑定→404000 / BLOCKED/RETIRED→403002）与 assertion 验签在事务外由 Fetcher 完成；
     * 本方法做条件更新 `sign_count`（0 行 → 403001 并发重放或期间被封禁/退役），
     * 成功后用绑定 installId 重签 installToken（复用 AuthJwtService.signInstall）。
     * 唯一约束冲突由 [attestExisting] 路径处理；本方法不涉及。
     */
    fun recoverInstall(
        mc: ModuleCtx,
        attestation: InstallAttestation,
        newCounter: Long,
    ): CreateInstallRes {
        val projectId = mc.projectId!!
        val advanced = attestRepo.updateSignCount(mc, attestation.id, newCounter)
        if (!advanced) {
            throw ApiError(
                ErrorCode.ATTESTATION_FAILED,
                "attestation counter not advanced (replay or blocked)",
            )
        }
        val token = jwt.signInstall(attestation.installId.toString(), projectId)
        return CreateInstallRes(installId = attestation.installId, installToken = token)
    }

    /**
     * 存量补证（§6.7 服务端流程第 8 步，事务内权威判定，须在事务内调用——外层 Fetcher 包 globalTx）：
     * 1. FOR UPDATE 锁 install 行（[attestRepo.lockInstallRow]，行不存在 → 404001）；
     * 2. 锁内重查 key 绑定：同 install ACTIVE → 幂等 [AttestExistingRes.Idempotent]（不新增）；
     *    BLOCKED/RETIRED → 403002；其他 install → 409001；
     * 3. 无绑定：countActiveByInstall 满 5 把时 retireOldestActive → 插入 → [AttestExistingRes.Created]。
     * subject 唯一约束冲突（并发跨 install 绑定）由调用方 catch 后回滚（GlobalTx 自动），
     * 重查映射 10/409001（见 [attestExistingRecheckAfterConflict]）。
     * 不修改 core_auth_install 的 platform / storeType，也不重签 token（§6.7）。
     */
    fun attestExisting(
        mc: ModuleCtx,
        installId: UUID,
        provider: Int,
        subject: String?,
        challenge: String?,
        verifiedProof: com.ifmix.core.api.infra.attest.AttestGuard.VerifiedProof,
    ): AttestExistingRes {
        val projectId = mc.projectId!!
        val locked = attestRepo.lockInstallRow(mc, projectId, installId)
        if (!locked) {
            throw ApiError(ErrorCode.INSTALL_NOT_FOUND, "install not found")
        }
        val existing = subject?.let { attestRepo.findBySubject(mc, projectId, provider, it) }
        return when {
            existing == null -> {
                val activeCount = attestRepo.countActiveByInstall(mc, projectId, installId)
                if (activeCount >= MAX_ACTIVE_KEYS_PER_INSTALL) {
                    attestRepo.retireOldestActive(mc, projectId, installId)
                }
                // attestExisting 走到这里必然已验签通过 → Bound（verify_status=10 绑定行）
                saveAttestationRow(mc, projectId, installId, AttestationOutcome.Bound(verifiedProof, challenge!!))
                AttestExistingRes.Created
            }
            existing.installId == installId && existing.status == AttestationStatuses.ACTIVE ->
                AttestExistingRes.Idempotent
            existing.status == AttestationStatuses.BLOCKED || existing.status == AttestationStatuses.RETIRED ->
                throw ApiError(ErrorCode.ATTEST_KEY_BLOCKED, "attestation key is ${existing.status}")
            else ->
                throw ApiError(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL, "attestation key is bound to another install")
        }
    }

    /**
     * subject 唯一约束冲突（另一个 install 并发绑定了同一把 key）→ 回滚后重查映射（§6.7 第 8 步末行）：
     * 同 install 已有 ACTIVE → 幂等（返回 10）；否则 → 409001（由 Fetcher 抛错）。
     */
    fun attestExistingRecheckAfterConflict(
        mc: ModuleCtx,
        installId: UUID,
        provider: Int,
        subject: String?,
    ): Boolean {
        val row = subject?.let { attestRepo.findBySubject(mc, mc.projectId!!, provider, it) } ?: return true
        return row.installId == installId && row.status == AttestationStatuses.ACTIVE
    }

    /** 只读按 (projectId, provider, subject) 查绑定（§5.2：recover/attestExisting 验签前的预查与公钥读取，经 InstallFacade，只读不写业务表）。 */
    fun findAttestationBySubject(mc: ModuleCtx, projectId: String, provider: Int, subject: String): InstallAttestation? =
        attestRepo.findBySubject(mc, projectId, provider, subject)

    /** 生成 installId(=PK) + 写 core_auth_install + 签发 installToken(type=5, iid=PK)。header 字段从 mc.action 取；reg_ip=clientIp。 */
    fun createInstall(mc: ModuleCtx, deviceInfo: Map<String, Any?>?): CreateInstallRes {
        val projectId = mc.projectId!!
        val installId = UuidV7.generate()
        val now = Instant.now()
        installRepo.save(mc, Install {
            this.id = installId
            this.projectId = projectId
            this.platform = platformInt(mc.action.clientPlatform)
            this.deviceInfo = deviceInfo
            this.appVersion = mc.action.appVersion
            this.otaVersion = mc.action.otaVersion
            this.locale = mc.action.locale
            this.country = mc.action.country
            this.currency = mc.action.currency
            this.regIp = mc.action.clientIp
            this.firebaseInstallId = null
            this.fcmToken = null
            this.scanResultNotiEnabled = true
            this.fcmTokenValid = true
            this.deepResearchNotiEnabled = true
            this.createdAt = now
            this.updatedAt = now
        })
        val token = jwt.signInstall(installId.toString(), projectId)
        return CreateInstallRes(installId = installId, installToken = token)
    }

    /**
     * 按 (projectId, id) 更新，仅覆盖非空字段。id 即 installId，来自 token iid（调用方传入）。
     * reg_ip write-once：不更新，保留注册时值。
     */
    fun updateInstall(
        mc: ModuleCtx,
        installId: UUID,
        firebaseInstallId: String?,
        fcmToken: String?,
        deviceInfo: Map<String, Any?>?,
        scanResultNotiEnabled: Boolean?,
        deepResearchNotiEnabled: Boolean?,
    ): Boolean {
        val projectId = mc.projectId!!
        val existing = installRepo.findById(mc, projectId, installId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "install not found")
        val nextToken = if (!fcmToken.isNullOrBlank()) fcmToken else existing.fcmToken
        installRepo.save(mc, Install {
            this.id = existing.id
            this.projectId = projectId
            this.platform = platformInt(mc.action.clientPlatform) ?: existing.platform
            this.deviceInfo = deviceInfo ?: existing.deviceInfo
            this.appVersion = mc.action.appVersion ?: existing.appVersion
            this.otaVersion = mc.action.otaVersion ?: existing.otaVersion
            this.locale = mc.action.locale ?: existing.locale
            this.country = mc.action.country ?: existing.country
            this.currency = mc.action.currency ?: existing.currency
            this.regIp = existing.regIp // write-once：updateInstall 不改
            this.firebaseInstallId = firebaseInstallId ?: existing.firebaseInstallId
            this.fcmToken = nextToken
            this.scanResultNotiEnabled = scanResultNotiEnabled ?: existing.scanResultNotiEnabled
            this.fcmTokenValid = if (!fcmToken.isNullOrBlank()) true else existing.fcmTokenValid
            this.deepResearchNotiEnabled = deepResearchNotiEnabled ?: existing.deepResearchNotiEnabled
            this.createdAt = existing.createdAt
            this.updatedAt = Instant.now()
        })
        return true
    }

    fun findNotificationTarget(mc: ModuleCtx, installId: UUID): InstallNotificationTarget? =
        installRepo.findById(mc, mc.projectId!!, installId)?.let {
            InstallNotificationTarget(
                enabled = it.scanResultNotiEnabled,
                fcmToken = it.fcmToken,
                fcmTokenValid = it.fcmTokenValid,
                deepResearchNotiEnabled = it.deepResearchNotiEnabled,
            )
        }

    fun invalidateFcmToken(mc: ModuleCtx, installId: UUID, sentToken: String): Int =
        installRepo.invalidateFcmToken(mc, mc.projectId!!, installId, sentToken)


    /** 绑定 install↔customer：换绑（软删该 install 其它有效关系）+ upsert（插/复活/不动）。幂等。 */
    fun bind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteOtherActiveByInstall(mc, projectId, installId, keepCustomerId = customerId)
        val existing = relationRepo.findAnyByPair(mc, projectId, installId, customerId)
        when (val action = decideBindAction(existing?.id, existing?.deletedAt != null)) {
            BindAction.Insert -> relationRepo.save(mc, InstallCustomerRelation {
                this.id = UuidV7.generate()
                this.projectId = projectId
                this.installId = installId
                this.customerId = customerId
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            })
            is BindAction.Reactivate -> relationRepo.reactivate(mc, action.id)
            BindAction.NoOp -> Unit
        }
    }

    /** 解绑（logout）：软删有效关系。 */
    fun unbind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteActive(mc, projectId, installId, customerId)
    }

    /** 删除账号：软删该 customer 全部有效关系。 */
    fun unbindAllForCustomer(mc: ModuleCtx, projectId: String, customerId: UUID) {
        relationRepo.softDeleteAllActiveByCustomer(mc, projectId, customerId)
    }

    private fun platformInt(p: ClientPlatform?): Int? = when (p) {
        ClientPlatform.ANDROID -> 10
        ClientPlatform.IOS -> 20
        ClientPlatform.WEB -> 30
        null -> null
    }

    companion object {
        /** 每 install 的 ACTIVE key 上限（§5.4：满 5 把时最早一把 retire，补证 §6.7 按 5 把计）。 */
        const val MAX_ACTIVE_KEYS_PER_INSTALL = 5

        sealed interface BindAction {
            /** 无任何行 → 插新行。 */
            data object Insert : BindAction
            /** 已有有效行 → 幂等不动。 */
            data object NoOp : BindAction
            /** 有软删行 → 复活。 */
            data class Reactivate(val id: UUID) : BindAction
        }

        /**
         * 绑定判定（纯函数）：按 (install, customer) 的既有行状态决定 插/复活/不动。
         * @param existingId 既有行 id（含软删查得），null=无行
         * @param existingDeleted 既有行是否已软删
         */
        fun decideBindAction(existingId: UUID?, existingDeleted: Boolean): BindAction = when {
            existingId == null -> BindAction.Insert
            existingDeleted -> BindAction.Reactivate(existingId)
            else -> BindAction.NoOp
        }
    }
}
