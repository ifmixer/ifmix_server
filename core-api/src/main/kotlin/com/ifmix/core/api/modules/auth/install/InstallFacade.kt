package com.ifmix.core.api.modules.auth.install

import com.ifmix.core.api.entity.auth.install.InstallAttestation
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler
import com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler.AttestExistingRes
import com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler.CreateInstallRes
import org.springframework.stereotype.Service
import java.util.UUID


/** notification 模块读取 install 的最小公开视图。 */
data class InstallNotificationTarget(
    val enabled: Boolean,
    val fcmToken: String?,
    val fcmTokenValid: Boolean,
    val deepResearchNotiEnabled: Boolean = true,
)
@Service
class InstallFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: InstallAggHandler,
) {
    fun createInstall(ctx: ActionContext, deviceInfo: Map<String, Any?>?): CreateInstallRes =
        handler.createInstall(mcFactory.forProject(ctx), deviceInfo)

    /**
     * 带平台证明的 createInstall（§5.3 事务内绑定；[verifiedProof] 非 null 表示 VALID，
     * iOS VALID 时按 keyId 查已有绑定，已存在 → 403001(key_reused) 不建 install）。
     * 由 Fetcher 在 globalTx 内调用；[storeType] 缺省 NULL、∉{10,20} → 400000。
     */
    fun createInstallWithProof(
        ctx: ActionContext,
        deviceInfo: Map<String, Any?>?,
        storeType: Int?,
        outcome: AttestGuard.AttestationOutcome?,
    ): CreateInstallRes =
        handler.createInstallWithProof(mcFactory.forProject(ctx), deviceInfo, storeType, outcome)

    /**
     * iOS 找回（§3.3，事务内）：条件更新 sign_count（0 行 → 403001 并发重放/期间封禁）+ 重签 installToken。
     * 只读预查（无绑定 404000 / BLOCKED/RETIRED 403002）与 challenge 校验由 Fetcher 前置完成。
     */
    fun recoverInstall(ctx: ActionContext, attestation: InstallAttestation, newCounter: Long): CreateInstallRes =
        handler.recoverInstall(mcFactory.forProject(ctx), attestation, newCounter)

    /**
     * 存量补证（§6.7 第 8 步，事务内权威判定）：锁 install 行（不存在 → 404001）→ 锁内重查绑定 →
     * 幂等 10 / 403002 / 409001 / 满 5 把 retire 最早 + 插入。
     */
    fun attestExisting(
        ctx: ActionContext,
        installId: UUID,
        verifiedProof: AttestGuard.VerifiedProof,
        challenge: String,
    ): AttestExistingRes =
        handler.attestExisting(mcFactory.forProject(ctx), installId, verifiedProof.provider, verifiedProof.subject, challenge, verifiedProof)

    /** subject 唯一约束冲突（并发跨 install 绑定）后的重查：true = 本 install ACTIVE（幂等 10）；false → 409001。 */
    fun attestExistingRecheckAfterConflict(ctx: ActionContext, installId: UUID, provider: Int, subject: String?): Boolean =
        handler.attestExistingRecheckAfterConflict(mcFactory.forProject(ctx), installId, provider, subject)

    /**
     * 只读按 (projectId, provider, subject) 查 attestation 绑定（§5.2：recover/attestExisting 验签前的
     * 预查与公钥读取；infra→Facade 只读先例，参照 FirebaseAppRegistry → ProjectServerConfigFacade）。
     */
    fun findAttestationBySubject(ctx: ActionContext, provider: Int, subject: String): InstallAttestation? =
        handler.findAttestationBySubject(mcFactory.forProject(ctx), ctx.mustGetProjectId(), provider, subject)

    fun updateInstall(
        ctx: ActionContext,
        installId: UUID,
        firebaseInstallId: String?,
        fcmToken: String?,
        deviceInfo: Map<String, Any?>?,
        scanResultNotiEnabled: Boolean?,
        deepResearchNotiEnabled: Boolean?,
    ): Boolean = handler.updateInstall(
        mcFactory.forProject(ctx), installId, firebaseInstallId, fcmToken, deviceInfo,
        scanResultNotiEnabled, deepResearchNotiEnabled,
    )

    fun findNotificationTarget(ctx: ActionContext, installId: UUID): InstallNotificationTarget? =
        handler.findNotificationTarget(mcFactory.forProject(ctx), installId)

    fun invalidateFcmToken(ctx: ActionContext, installId: UUID, sentToken: String): Int =
        handler.invalidateFcmToken(mcFactory.forProject(ctx), installId, sentToken)

    // ===== 关系维护（供 AuthAggHandler 在 auth 流程内调用） =====

    fun bind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.bind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.unbind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbindAllForCustomer(ctx: ActionContext, customerId: UUID) =
        handler.unbindAllForCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId(), customerId)
}
