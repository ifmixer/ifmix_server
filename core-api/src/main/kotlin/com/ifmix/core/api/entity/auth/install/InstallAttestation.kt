package com.ifmix.core.api.entity.auth.install

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/** attestation 状态编码（Int 全链路透传），码表见 [AttestationStatuses]。 */
typealias AttestationStatus = Int

/** core_install_attestation.status 码表（10 起步长 10）。 */
object AttestationStatuses {
    /** 有效：可 recover、可做新绑定。 */
    const val ACTIVE: AttestationStatus = 10
    /** 风险封禁：禁止 recover / 新绑定（已签发的 installToken 不吊销，规格 §5.4）。 */
    const val BLOCKED: AttestationStatus = 20
    /** 超出每个 install 的 ACTIVE 上限后轮换下来：不能 recover，也不能复活。 */
    const val RETIRED: AttestationStatus = 30
}

/**
 * install 平台证明持久凭证（App Attest / Play Integrity），只存 VALID 的长期凭证与绑定。
 * installId 为逻辑外键 core_install.id（UUID，不用 @ManyToOne）；subject = iOS keyId（Android NULL）。
 * signals / evidence 为 JSONB（@Serialized）；attestation_object 原文由 core-job 回填 receipt 成功后清空。
 */
@Entity
@Table(name = "core_install_attestation")
interface InstallAttestation : BaseProjectEntity {

    /** 逻辑外键 core_install.id。 */
    @Column(name = "install_id")
    val installId: UUID

    /** 平台证明 provider Int 码：110=APP_ATTEST / 120=PLAY_INTEGRITY。 */
    val provider: Int

    /** iOS keyId；Android 为 NULL。(project_id, provider, subject) 唯一（subject 非空时）。 */
    val subject: String?

    val publicKey: ByteArray?

    /** 原始 attestation（≤16KB）；core-job 回填 receipt 成功后清空。 */
    @Column(name = "attestation_object")
    val attestationObject: ByteArray?

    /** iOS assertion counter，单调递增；recover 的条件更新依赖它非 NULL。 */
    @Column(name = "sign_count")
    val signCount: Long

    /** Apple 回填的 receipt（core-job 异步写入；一期不轮换）。 */
    val receipt: ByteArray?

    @Column(name = "receipt_expires_at")
    val receiptExpiresAt: Instant?

    /** receipt 回填 / fraud metric 下次刷新时间（core-job 退避策略写入）。 */
    @Column(name = "next_refresh_at")
    val nextRefreshAt: Instant?

    /** 回填 / 刷新连续失败次数（指数退避，封顶 24h）。 */
    @Column(name = "refresh_failure_count")
    val refreshFailureCount: Int

    /** DeviceCheck two bits（0..3），core-job 刷新写入。 */
    @Column(name = "fraud_metric")
    val fraudMetric: Int?

    /** 验证信号（固定键集合，JSONB，规格 §5.7）。 */
    @Serialized
    val signals: Map<String, Any?>

    /** 佐证（原始 verdict / 证书摘要，不含原始 token，JSONB）；90 天后由 core-job 清空。 */
    @Serialized
    val evidence: Map<String, Any?>?

    val status: AttestationStatus

    @Column(name = "last_used_at")
    val lastUsedAt: Instant?
}
