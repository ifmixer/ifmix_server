package com.ifmix.core.api.modules.auth.install.handler

import com.ifmix.core.api.entity.auth.install.AttestationStatuses
import com.ifmix.core.api.entity.auth.install.AttestationVerifyStatuses
import com.ifmix.core.api.entity.auth.install.Install
import com.ifmix.core.api.entity.auth.install.InstallAttestation
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.api.core.common.auth.testAuthJwtKeys
import com.ifmix.core.api.modules.auth.install.repo.InstallAttestationRepository
import com.ifmix.core.api.modules.auth.install.repo.InstallCustomerRelationRepository
import com.ifmix.core.api.modules.auth.install.repo.InstallRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat

/**
 * InstallAggHandler 事务内绑定单测（规格 §7「InstallAggHandler」+ storeType）：
 * - createInstallWithProof：iOS VALID 已存在 key → 403001(key_reused) 且不建 install（OBSERVE/ENFORCE 同口径，与 mode 无关）；
 *   不存在 → 建 install（VALID 时 platform 由 provider 110 派生=20）+ attestation 行（status=10、sign_count=0、原文入库）。
 * - storeType：缺省 NULL；10/20 正常写入；其它值 400000。
 * - recoverInstall：条件更新 0 行 → 403001（并发重放/期间被封禁）；成功 → 重签 installToken。
 * - attestExisting：锁 install 行不存在 → 404001；满 5 把时 retire 最早 + 插入；subject 唯一冲突重查映射。
 */
class InstallAggHandlerAttestTest {

    private val projectId = "test-app"
    private val installId = UUID.randomUUID()
    private val otherInstallId = UUID.randomUUID()

    private class CapturingInstallRepo : InstallRepository() {
        var saved: Install? = null
        var savedCount = 0
        override fun save(mc: ModuleCtx, entity: Install): Boolean {
            saved = entity
            savedCount++
            return true
        }
    }

    private class StubAttestRepo(
        val mock: InstallAttestationRepository,
    ) {
        var found: InstallAttestation? = null
        var inserted: MutableList<InstallAttestation> = mutableListOf()
        var signCountAdvanced = true
        var lockResult = true
        var activeCount = 0
        var retired = false

        fun wire() {
            whenever(mock.findBySubject(any(), any(), any(), any())).thenAnswer { found }
            whenever(mock.insert(any(), any())).thenAnswer {
                inserted.add(it.arguments[1] as InstallAttestation)
                true
            }
            whenever(mock.updateSignCount(any(), any(), any())).thenAnswer { signCountAdvanced.also { signCountAdvanced = false } }
            whenever(mock.lockInstallRow(any(), any(), any())).thenAnswer { lockResult }
            whenever(mock.countActiveByInstall(any(), any(), any())).thenAnswer { activeCount }
            whenever(mock.retireOldestActive(any(), any(), any())).thenAnswer { retired.also { retired = true } }
        }

        val delegate: InstallAttestationRepository get() = mock
    }

    private lateinit var installRepo: CapturingInstallRepo
    private lateinit var attestRepo: StubAttestRepo
    private lateinit var attestMock: InstallAttestationRepository
    private lateinit var jwt: AuthJwtService
    private lateinit var handler: InstallAggHandler
    private lateinit var mc: ModuleCtx

    @BeforeEach
    fun setUp() {
        installRepo = CapturingInstallRepo()
        attestMock = mock<InstallAttestationRepository>()
        attestRepo = StubAttestRepo(attestMock)
        attestRepo.wire()
        jwt = AuthJwtService(testAuthJwtKeys(), issuer = "test-issuer")
        handler = InstallAggHandler(installRepo, InstallCustomerRelationRepository(), attestRepo.delegate, jwt)
        mc = ModuleCtx(action = ActionContext(projectId = projectId), sql = mock<KSqlClient>())
    }

    private fun proof(keyId: String = "key-1", provider: Int = 110) = AttestGuard.VerifiedProof(
        provider = provider,
        subject = keyId,
        publicKey = byteArrayOf(2),
        attestationObject = byteArrayOf(3),
        signals = mapOf("env" to "production"),
        evidence = mapOf("provider" to provider),
    )

    // ===== createInstallWithProof =====

    @Test
    fun `VALID ios proof creates install with derived platform 20 and writes attestation row`() {
        val res = handler.createInstallWithProof(mc, null, 10, AttestGuard.AttestationOutcome.Bound(proof(), "challenge-1"))
        assertThat(installRepo.saved!!.id).isEqualTo(res.installId)
        assertThat(installRepo.saved!!.platform).isEqualTo(20) // §4.2：110 → IOS
        assertThat(installRepo.saved!!.storeType).isEqualTo(10)
        val row = attestRepo.inserted.single()
        assertThat(row.status).isEqualTo(AttestationStatuses.ACTIVE)
        assertThat(row.signCount).isZero()
        assertThat(row.provider).isEqualTo(110)
        assertThat(row.subject).isEqualTo("key-1")
        assertThat(row.publicKey).containsExactly(2)
        assertThat(row.attestationObject).containsExactly(3)
        // token iid == installId
        assertThat(jwt.verify(res.installToken)!!.installId).isEqualTo(res.installId.toString())
    }

    @Test
    fun `existing key binding to 403001 key_reused and no install created`() {
        attestRepo.found = rowFor(otherInstallId, AttestationStatuses.ACTIVE)
        val e = assertThrows<ApiError> { handler.createInstallWithProof(mc, null, 10, AttestGuard.AttestationOutcome.Bound(proof(), "challenge-1")) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
        assertThat(installRepo.savedCount).isZero // 不建 install
        assertThat(attestRepo.inserted).isEmpty()
    }

    @Test
    fun `no proof creates install without attestation row and header platform`() {
        val res = handler.createInstallWithProof(mc, null, null, null)
        assertThat(res.installId).isEqualTo(installRepo.saved!!.id)
        assertThat(attestRepo.inserted).isEmpty()
    }

    @Test
    fun `storeType default null is stored as null`() {
        handler.createInstallWithProof(mc, null, null, null)
        assertThat(installRepo.saved!!.storeType).isNull()
    }

    @Test
    fun `storeType outside 10 20 to 400000`() {
        val e = assertThrows<ApiError> { handler.createInstallWithProof(mc, null, 30, null) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `Failed outcome records attempt row verify_status 20 with raw material`() {
        handler.createInstallWithProof(
            mc, null, null,
            AttestGuard.AttestationOutcome.Failed(110, "key-f", byteArrayOf(9), "challenge-f", AttestGuard.Reason.CHAIN_INVALID),
        )
        val row = attestRepo.inserted.single()
        assertThat(row.verifyStatus).isEqualTo(AttestationVerifyStatuses.INVALID)
        assertThat(row.status).isEqualTo(AttestationStatuses.NOT_BOUND)
        assertThat(row.subject).isEqualTo("key-f")
        assertThat(row.challenge).isEqualTo("challenge-f")
        assertThat(row.attestationObject!!).isEqualTo(byteArrayOf(9)) // 原始 proof 留痕，供离线重验
        assertThat(row.evidence!!["reason"]).isEqualTo("chain_invalid")
    }

    @Test
    fun `NotEvaluated outcome records attempt row verify_status 30`() {
        handler.createInstallWithProof(
            mc, null, null,
            AttestGuard.AttestationOutcome.NotEvaluated(120, null, byteArrayOf(7), null, null),
        )
        val row = attestRepo.inserted.single()
        assertThat(row.verifyStatus).isEqualTo(AttestationVerifyStatuses.NOT_EVALUATED)
        assertThat(row.status).isEqualTo(AttestationStatuses.NOT_BOUND)
        assertThat(row.provider).isEqualTo(120)
        assertThat(row.challenge).isNull()
    }

    @Test
    fun `Bound outcome records verify_status 10 ACTIVE with raw material`() {
        handler.createInstallWithProof(mc, null, null, AttestGuard.AttestationOutcome.Bound(proof(), "challenge-1"))
        val row = attestRepo.inserted.single()
        assertThat(row.verifyStatus).isEqualTo(AttestationVerifyStatuses.VALID)
        assertThat(row.status).isEqualTo(AttestationStatuses.ACTIVE)
        assertThat(row.challenge).isEqualTo("challenge-1")
        assertThat(row.attestationObject!!).isEqualTo(byteArrayOf(3))
    }

    // ===== recoverInstall =====

    @Test
    fun `recover advances counter and re signs token`() {
        val row = rowFor(installId, AttestationStatuses.ACTIVE)
        val res = handler.recoverInstall(mc, row, 3)
        assertThat(res.installId).isEqualTo(installId)
        assertThat(jwt.verify(res.installToken)!!.installId).isEqualTo(installId.toString())
    }

    @Test
    fun `recover conditional update zero rows to 403001`() {
        attestRepo.signCountAdvanced = false
        val row = rowFor(installId, AttestationStatuses.ACTIVE)
        val e = assertThrows<ApiError> { handler.recoverInstall(mc, row, 3) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    // ===== attestExisting =====

    @Test
    fun `attestExisting install row missing to 404001`() {
        attestRepo.lockResult = false
        val e = assertThrows<ApiError> { handler.attestExisting(mc, installId, 110, "key-1", "challenge-1", proof()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INSTALL_NOT_FOUND)
        assertThat(attestRepo.inserted).isEmpty()
    }

    @Test
    fun `attestExisting same install ACTIVE to idempotent no new row`() {
        attestRepo.found = rowFor(installId, AttestationStatuses.ACTIVE)
        val res = handler.attestExisting(mc, installId, 110, "key-1", "challenge-1", proof())
        assertThat(res).isInstanceOf(InstallAggHandler.AttestExistingRes.Idempotent::class.java)
        assertThat(attestRepo.inserted).isEmpty()
    }

    @Test
    fun `attestExisting BLOCKED to 403002`() {
        attestRepo.found = rowFor(installId, AttestationStatuses.BLOCKED)
        val e = assertThrows<ApiError> { handler.attestExisting(mc, installId, 110, "key-1", "challenge-1", proof()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BLOCKED)
    }

    @Test
    fun `attestExisting RETIRED to 403002`() {
        attestRepo.found = rowFor(installId, AttestationStatuses.RETIRED)
        val e = assertThrows<ApiError> { handler.attestExisting(mc, installId, 110, "key-1", "challenge-1", proof()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BLOCKED)
    }

    @Test
    fun `attestExisting other install ACTIVE to 409001`() {
        attestRepo.found = rowFor(otherInstallId, AttestationStatuses.ACTIVE)
        val e = assertThrows<ApiError> { handler.attestExisting(mc, installId, 110, "key-1", "challenge-1", proof()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL)
    }

    @Test
    fun `attestExisting fifth key retires oldest then inserts`() {
        attestRepo.activeCount = 5
        val res = handler.attestExisting(mc, installId, 110, "key-new", "challenge-1", proof("key-new"))
        assertThat(res).isInstanceOf(InstallAggHandler.AttestExistingRes.Created::class.java)
        assertThat(attestRepo.retired).isTrue // 满 5 把 → retire 最早一把
        val row = attestRepo.inserted.single()
        assertThat(row.subject).isEqualTo("key-new")
    }

    @Test
    fun `attestExisting below cap inserts without retiring`() {
        attestRepo.activeCount = 2
        handler.attestExisting(mc, installId, 110, "key-new", "challenge-1", proof("key-new"))
        verify(attestMock, never()).retireOldestActive(any(), any(), any())
    }

    @Test
    fun `attestExisting recheck after unique conflict maps to re bind outcome`() {
        // 本 install 已有 ACTIVE 绑定 → 10（true）；别人 install → 409001（false）
        attestRepo.found = rowFor(installId, AttestationStatuses.ACTIVE)
        assertThat(handler.attestExistingRecheckAfterConflict(mc, installId, 110, "key-1")).isTrue
        attestRepo.found = rowFor(otherInstallId, AttestationStatuses.ACTIVE)
        assertThat(handler.attestExistingRecheckAfterConflict(mc, installId, 110, "key-1")).isFalse
    }

    // ===== 辅助 =====

    private fun rowFor(installId: UUID, status: Int): InstallAttestation {
        // 裸 projectId 在 draft lambda 内会解析到 receiver 的 getProjectId（未加载）→ 先捕获到局部
        val pid = projectId
        return InstallAttestation {
            this.id = UUID.randomUUID()
            this.projectId = pid
            this.installId = installId
            this.provider = 110
            this.subject = "key-1"
            this.publicKey = byteArrayOf(9)
            this.attestationObject = byteArrayOf(1)
            this.signCount = 0
            this.status = status
            this.signals = emptyMap()
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
    }
}
