package com.ifmix.core.api.bff.graphql.customer.auth

import com.ifmix.core.api.entity.auth.install.AttestationStatuses
import com.ifmix.core.api.entity.auth.install.InstallAttestation
import com.ifmix.core.api.infra.attest.AttestChallengeCodec
import com.ifmix.core.api.infra.attest.AttestConfig
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.attest.AttestGuard.ProofBundle
import com.ifmix.core.api.infra.attest.AttestGuard.VerifiedProof
import com.ifmix.core.api.infra.attest.AttestGuard.Verification
import com.ifmix.core.api.infra.attest.AttestMode
import com.ifmix.core.api.infra.attest.IosAttestConfig
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler.AttestExistingRes
import com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler.CreateInstallRes
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

/**
 * InstallFetcher 编排单测（规格 §7：createInstall 日窗口 12 项 + attestExisting 全分支 + 下游限流 key 契约）。
 *
 * 策略：AttestGuard / RateLimiter / InstallFacade / ProjectServerConfigFacade 全 mock——key 名与 limit 值即行为契约；
 * GlobalTxRunner 用 mock（withTx 直通 lambda，事务边界由 T7 e2e 覆盖）。Guard 方法一律 anyOrNull() 全匹配打桩，
 * 避免 ProofBundle（ByteArray 字段）数据类相等性与可空泛型匹配器的坑；Guard 内部判定矩阵在 AttestGuardTest 覆盖。
 *
 * 不在此重测（RateLimiterTest / e2e 覆盖）：跨 UTC 零点切 key、配置重启生效、并发 5 把 ACTIVE。
 */
class InstallFetcherAttestTest {

    private val pid = "p1"
    private val ip = "1.2.3.4"
    private val installId = UUID.randomUUID()

    private lateinit var dfe: DgsDataFetchingEnvironment
    private lateinit var installFacade: InstallFacade
    private lateinit var ctxProvider: ActionContextProvider
    private lateinit var rateLimiter: RateLimiter
    private lateinit var attestGuard: AttestGuard
    private lateinit var serverConfigFacade: ProjectServerConfigFacade
    private lateinit var fetcher: InstallFetcher

    private val entryKey = "ratelimit:$pid:install:ip:min:$ip"
    private val attestedDayKey = "ratelimit:$pid:install:ip:day:attested:$ip"
    private val unverifiedDayKey = "ratelimit:$pid:install:ip:day:unverified:$ip"
    private val challengeKey = "ratelimit:$pid:attest-challenge:ip:min:$ip"
    private val recoverKey = "ratelimit:$pid:recover-install:ip:min:$ip"
    private val attestIpKey = "ratelimit:$pid:attest-existing:ip:min:$ip"
    private val attestDayKey = "ratelimit:$pid:attest-existing:install:day:$installId"

    private fun baseCtx() = ActionContext(projectId = pid, clientIp = ip, clientPlatform = ClientPlatform.IOS)
    private fun installTokenCtx() = baseCtx().copy(installId = installId, tokenType = 5, actorId = null)

    private fun validProof() = VerifiedProof(
        provider = 110, subject = "key1", publicKey = byteArrayOf(1),
        attestationObject = byteArrayOf(2), signals = mapOf("env" to "production"), evidence = emptyMap(),
    )

    private fun validVerification() = Verification.Valid(proof = validProof(), challenge = "ch")
    private fun invalidVerification() = Verification.Invalid(AttestGuard.Reason.ATTESTATION_INVALID)

    @BeforeEach
    fun setUp() {
        dfe = mock()
        ctxProvider = mock()
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(baseCtx())
        installFacade = mock()
        rateLimiter = mock()
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        attestGuard = mock()
        val globalTx = mock<GlobalTxRunner>()
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer { ((it.arguments[1]) as (ActionContext) -> Any)(baseCtx()) }
        serverConfigFacade = mock()
        whenever(serverConfigFacade.findAttestConfig(pid)).thenReturn(
            AttestConfig(AttestMode.OBSERVE, IosAttestConfig("T", "com.x", true, null, null), null, emptyList()),
        )
        fetcher = InstallFetcher(installFacade, globalTx, ctxProvider, rateLimiter, RateLimitProperties(), attestGuard, serverConfigFacade)
    }

    private fun stubTxCreate() {
        whenever(installFacade.createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(CreateInstallRes(installId, "tok"))
    }

    /** VALID 路径：parseProofInput→bundle、verifyProof→Valid、decide→VERIFIED。Guard 参数全匹配。 */
    private fun stubCreateValid() {
        stubTxCreate()
        whenever(attestGuard.parseProofInput(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "key1", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(validVerification())
        whenever(attestGuard.decideCreateInstall(any(), anyOrNull())).thenReturn(AttestGuard.CreateInstallDecision.VERIFIED)
    }

    /** 无 proof 路径：NoProof + NOT_ATTEMPTED。 */
    private fun stubCreateNoProof() {
        stubTxCreate()
        whenever(attestGuard.parseProofInput(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(null)
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(Verification.NoProof)
        whenever(attestGuard.decideCreateInstall(any(), anyOrNull())).thenReturn(AttestGuard.CreateInstallDecision.NOT_ATTEMPTED)
    }

    // ===== createInstall =====

    @Test
    fun `platform header must match action ios action rejects android and missing platform`() {
        val androidCtx = baseCtx().copy(clientPlatform = ClientPlatform.ANDROID)
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(androidCtx)
        val e = assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        verify(rateLimiter, never()).check(any(), any(), any())

        val noPlatformCtx = baseCtx().copy(clientPlatform = null)
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(noPlatformCtx)
        val e2 = assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        assertThat(e2.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `proof provider mismatch from guard surfaces as 400000 and install is not created`() {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(baseCtx())
        // provider 与 action 不一致的拒绝在真 AttestGuard.parseProofInput（AttestGuardTest 覆盖）；此处验证 fetcher 不吞错
        whenever(attestGuard.parseProofInput(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(ApiError(ErrorCode.INVALID_REQUEST, "proof provider mismatch"))
        val e = assertThrows<ApiError> { fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>())) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        verify(installFacade, never()).createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }



    @Test
    fun `entry minute window limited to 429000 with retryAfterSec`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(entryKey), eq(100))).thenReturn(RateLimitResult.Limited(12))
        val e = assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(12L)
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
    }

    @Test
    fun `VALID request uses attested day counter and maps to status 10`() {
        stubCreateValid()
        val res = fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>()))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(attestedDayKey), eq(1000))
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(unverifiedDayKey), eq(100))
        assertThat(res.attestationStatus).isEqualTo(10)
    }

    @Test
    fun `no-proof request uses unverified day counter and maps to status 30`() {
        stubCreateNoProof()
        val res = fetcher.createIosInstall(dfe, null)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(unverifiedDayKey), eq(100))
        assertThat(res.attestationStatus).isEqualTo(30)
        verify(installFacade).createInstallWithProof(any(), anyOrNull(), eq(null), eq(null))
    }

    @Test
    fun `OBSERVE INVALID maps to status 20 and counts into unverified bucket`() {
        stubTxCreate()
        whenever(attestGuard.parseProofInput(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "bad", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(invalidVerification())
        whenever(attestGuard.decideCreateInstall(any(), anyOrNull())).thenReturn(AttestGuard.CreateInstallDecision.NOT_PERSISTED)
        val res = fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>()))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(unverifiedDayKey), eq(100))
        assertThat(res.attestationStatus).isEqualTo(20)
    }

    @Test
    fun `ENFORCE stage rejection does not touch day counters`() {
        whenever(attestGuard.parseProofInput(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "k", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(invalidVerification())
        whenever(attestGuard.decideCreateInstall(any(), anyOrNull()))
            .thenThrow(AttestGuard.GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "attestation failed")))
        assertThrows<AttestGuard.GuardError> { fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>())) }
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
        verify(installFacade, never()).createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `attested day counter limited to 429002 with retryAfterSec to UTC midnight`() {
        stubCreateValid()
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(attestedDayKey), eq(1000))).thenReturn(RateLimitResult.Limited(43_200))
        val e = assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INSTALL_DAILY_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(43_200L)
    }

    @Test
    fun `day counters are independent - VALID is not blocked by a full unverified bucket`() {
        stubCreateValid()
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(unverifiedDayKey), eq(100))).thenReturn(RateLimitResult.Limited(60))
        val res = fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>()))
        assertThat(res.attestationStatus).isEqualTo(10)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(attestedDayKey), eq(1000))
    }

    @Test
    fun `key_reused in transaction stage does not refund day counter`() {
        stubCreateNoProof()
        whenever(installFacade.createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(ApiError(ErrorCode.ATTESTATION_FAILED, "key_reused"))
        assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        // 日额度已扣且不退（无 refund 机制，§4.6）：unverified 桶被扣，attested 桶从未触碰
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(attestedDayKey), eq(1000))
    }

    @Test
    fun `concurrent unique-constraint conflict maps to 403001 not 500`() {
        // §5.3 / review [中]：并发同 keyId（两个不同 challenge）唯一约束兜底 → 失败方回滚 → 403001(key_reused)，
        // 客户端靠这个码转 recover（而不是 500000 INTERNAL）
        stubCreateNoProof()
        whenever(installFacade.createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(org.springframework.dao.DataIntegrityViolationException("duplicate key"))
        val ex = assertThrows<ApiError> { fetcher.createIosInstall(dfe, null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    @Test
    fun `redis degraded windows allow the request through`() {
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Degraded)
        stubCreateNoProof()
        val res = fetcher.createIosInstall(dfe, null)
        assertThat(res.attestationStatus).isEqualTo(30)
    }

    @Test
    fun `proof and proofStatus together to 400000 and nothing consumed`() {
        whenever(attestGuard.parseProofInput(any(), anyOrNull(), anyOrNull()))
            .thenThrow(ApiError(ErrorCode.INVALID_REQUEST, "proof and proofStatus are mutually exclusive"))
        assertThrows<ApiError> { fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>(), "proofStatus" to 20)) }
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
        verify(attestGuard, never()).consume(any())
    }

    @Test
    fun `illegal proofStatus to 400000`() {
        whenever(attestGuard.parseProofInput(any(), eq(30), anyOrNull()))
            .thenThrow(ApiError(ErrorCode.INVALID_REQUEST, "invalid proofStatus"))
        assertThrows<ApiError> { fetcher.createIosInstall(dfe, mapOf("proofStatus" to 30)) }
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
    }

    @Test
    fun `replay at consume stage to 403001, day counter already deducted and not refunded`() {
        stubCreateValid()
        whenever(attestGuard.consume(any())).thenThrow(AttestGuard.GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "replay")))
        val e = assertThrows<AttestGuard.GuardError> { fetcher.createIosInstall(dfe, mapOf("proof" to emptyMap<String, Any>())) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(attestedDayKey), eq(1000))
        verify(installFacade, never()).createInstallWithProof(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `storeType 20 passes to handler and VALID provider 110 mismatch logs store_mismatch`() {
        stubCreateValid()
        fetcher.createIosInstall(dfe, mapOf("storeType" to 20))
        verify(installFacade).createInstallWithProof(any(), anyOrNull(), eq(20), anyOrNull())
        verify(attestGuard).logStoreMismatch(eq(pid), eq(110), eq(20))
    }

    @Test
    fun `storeType null maps to null and no mismatch log`() {
        stubCreateNoProof()
        fetcher.createIosInstall(dfe, null)
        verify(installFacade).createInstallWithProof(any(), anyOrNull(), eq(null), eq(null))
        verify(attestGuard, never()).logStoreMismatch(any(), any(), any())
    }

    // ===== createAttestChallenge =====

    @Test
    fun `challenge disabled to enabled false with null challenge and 270s budget`() {
        whenever(attestGuard.isChallengeEnabled(any(), anyOrNull())).thenReturn(false)
        val res = fetcher.createAttestChallenge(dfe)
        assertThat(res.enabled).isFalse()
        assertThat(res.challenge).isNull()
        assertThat(res.expiresInSec).isEqualTo(AttestChallengeCodec.CHALLENGE_CLIENT_TTL_SEC)
        verify(rateLimiter).check(eq(Window.MINUTE), eq(challengeKey), eq(100))
    }

    @Test
    fun `challenge enabled to issued`() {
        whenever(attestGuard.isChallengeEnabled(any(), anyOrNull())).thenReturn(true)
        whenever(attestGuard.issueChallenge(any())).thenReturn("challenge")
        val res = fetcher.createAttestChallenge(dfe)
        assertThat(res.enabled).isTrue()
        assertThat(res.challenge).isEqualTo("challenge")
    }

    @Test
    fun `challenge minute window limited to 429000`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(challengeKey), eq(100))).thenReturn(RateLimitResult.Limited(7))
        val e = assertThrows<ApiError> { fetcher.createAttestChallenge(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(7L)
    }

    // ===== recoverInstall =====

    private fun recoverInput() = mapOf(
        "keyId" to "key1",
        "assertion" to java.util.Base64.getEncoder().encodeToString(byteArrayOf(4, 5)),
        "challenge" to "ch",
    )

    private fun attestationRow(installId: UUID = this.installId, status: Int = AttestationStatuses.ACTIVE, signCount: Long = 0, publicKey: ByteArray? = byteArrayOf(9)) =
        InstallAttestation {
            this.id = UUID.randomUUID()
            this.projectId = pid
            this.installId = installId
            this.provider = 110
            this.subject = "key1"
            this.publicKey = publicKey
            this.attestationObject = byteArrayOf(1)
            this.signCount = signCount
            this.status = status
            this.signals = emptyMap()
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }

    @Test
    fun `recover unbound key to 404000`() {
        whenever(attestGuard.checkRecoverChallenge(any(), any())).thenAnswer { }
        whenever(installFacade.findAttestationBySubject(any(), any(), any())).thenReturn(null)
        val e = assertThrows<ApiError> { fetcher.recoverInstall(dfe, recoverInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
        verify(rateLimiter).check(eq(Window.MINUTE), eq(recoverKey), eq(10))
    }

    @Test
    fun `recover BLOCKED key to 403002`() {
        whenever(attestGuard.checkRecoverChallenge(any(), any())).thenAnswer { }
        whenever(installFacade.findAttestationBySubject(any(), any(), any()))
            .thenReturn(attestationRow(status = AttestationStatuses.BLOCKED))
        val e = assertThrows<ApiError> { fetcher.recoverInstall(dfe, recoverInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BLOCKED)
    }

    @Test
    fun `recover missing challenge secret to 503002`() {
        whenever(attestGuard.checkRecoverChallenge(any(), any()))
            .thenThrow(AttestGuard.GuardError(ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "secret missing")))
        val e = assertThrows<AttestGuard.GuardError> { fetcher.recoverInstall(dfe, recoverInput()) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    @Test
    fun `recover success to re-signs token attestationStatus fixed 10`() {
        val verifier = mock<com.ifmix.core.api.infra.attest.AppAttestVerifier>()
        whenever(attestGuard.appAttestVerifier).thenReturn(verifier)
        whenever(verifier.verifyAssertion(any(), any(), any(), any(), any(), any(), any())).thenReturn(
            com.ifmix.core.api.infra.attest.AppAttestVerification.AssertionSuccess(newCounter = 2),
        )
        whenever(attestGuard.checkRecoverChallenge(any(), any())).thenAnswer { }
        whenever(installFacade.findAttestationBySubject(any(), any(), any()))
            .thenReturn(attestationRow(signCount = 1, publicKey = byteArrayOf(9)))
        whenever(installFacade.recoverInstall(any(), any(), any())).thenReturn(CreateInstallRes(installId, "new-token"))
        whenever(attestGuard.consumeChallenge(any())).thenAnswer { }
        val res = fetcher.recoverInstall(dfe, recoverInput())
        assertThat(res.attestationStatus).isEqualTo(10)
        assertThat(res.installId).isEqualTo(installId)
        verify(installFacade).recoverInstall(any(), any(), eq(2L))
    }

    @Test
    fun `recover minute window limited to 429000`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(recoverKey), eq(10))).thenReturn(RateLimitResult.Limited(9))
        val e = assertThrows<ApiError> { fetcher.recoverInstall(dfe, recoverInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(9L)
    }

    // ===== attestExisting =====

    private fun attestInput() = mapOf("proof" to mapOf("provider" to 110, "appAttest" to emptyMap<String, Any>()))

    private fun stubInstallToken() {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(installTokenCtx())
    }

    private fun stubValidAttest() {
        whenever(attestGuard.parseProofInput(any(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "key1", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(validVerification())
        whenever(attestGuard.isAttestationEnabled(any())).thenReturn(true)
        whenever(installFacade.findAttestationBySubject(any(), any(), any())).thenReturn(null)
    }

    @Test
    fun `attestExisting with customer token to 401000`() {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(
            baseCtx().copy(installId = installId, tokenType = 10, actorId = UUID.randomUUID()),
        )
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `attestExisting without installId to 401000`() {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(
            baseCtx().copy(tokenType = 5, actorId = null, installId = null),
        )
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `attestExisting install row missing to 404001`() {
        stubInstallToken()
        stubValidAttest()
        whenever(installFacade.attestExisting(any(), any(), any()))
            .thenThrow(ApiError(ErrorCode.INSTALL_NOT_FOUND, "install not found"))
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INSTALL_NOT_FOUND)
    }

    @Test
    fun `attestExisting BLOCKED key to 403002 new-key quota not touched`() {
        stubInstallToken()
        stubValidAttest()
        whenever(installFacade.findAttestationBySubject(any(), any(), any()))
            .thenReturn(attestationRow(status = AttestationStatuses.BLOCKED))
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BLOCKED)
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(attestDayKey), eq(3))
    }

    @Test
    fun `attestExisting key bound to another install to 409001`() {
        stubInstallToken()
        stubValidAttest()
        whenever(installFacade.findAttestationBySubject(any(), any(), any()))
            .thenReturn(attestationRow(installId = UUID.randomUUID(), status = AttestationStatuses.ACTIVE))
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL)
    }

    @Test
    fun `attestExisting idempotent active binding to 10 without touching quota or consume`() {
        stubInstallToken()
        stubValidAttest()
        whenever(installFacade.findAttestationBySubject(any(), any(), any()))
            .thenReturn(attestationRow(status = AttestationStatuses.ACTIVE))
        val res = fetcher.attestExisting(dfe, attestInput())
        assertThat(res.attestationStatus).isEqualTo(10)
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
        verify(attestGuard, never()).consume(any())
    }

    @Test
    fun `attestExisting INVALID proof to 20 even under ENFORCE`() {
        stubInstallToken()
        whenever(attestGuard.parseProofInput(any(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "k", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull())).thenReturn(invalidVerification())
        whenever(attestGuard.isAttestationEnabled(any())).thenReturn(true)
        val res = fetcher.attestExisting(dfe, attestInput())
        assertThat(res.attestationStatus).isEqualTo(20)
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), any(), any())
    }

    @Test
    fun `attestExisting server OFF to 30`() {
        stubInstallToken()
        whenever(attestGuard.isAttestationEnabled(any())).thenReturn(false)
        val res = fetcher.attestExisting(dfe, attestInput())
        assertThat(res.attestationStatus).isEqualTo(30)
    }

    @Test
    fun `attestExisting fourth distinct key in a day to 429002 with retryAfterSec challenge not consumed`() {
        stubInstallToken()
        stubValidAttest()
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(attestDayKey), eq(3))).thenReturn(RateLimitResult.Limited(36_000))
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.INSTALL_DAILY_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(36_000L)
        verify(attestGuard, never()).consume(any())
    }

    @Test
    fun `attestExisting replay at consume to 403001`() {
        stubInstallToken()
        stubValidAttest()
        whenever(attestGuard.consume(any())).thenThrow(AttestGuard.GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "replay")))
        val e = assertThrows<AttestGuard.GuardError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    @Test
    fun `attestExisting success to 10 with per-install day quota and ip minute windows`() {
        stubInstallToken()
        stubValidAttest()
        whenever(installFacade.attestExisting(any(), any(), any())).thenReturn(AttestExistingRes.Created)
        val res = fetcher.attestExisting(dfe, attestInput())
        assertThat(res.attestationStatus).isEqualTo(10)
        verify(rateLimiter).check(eq(Window.MINUTE), eq(attestIpKey), eq(10))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(attestDayKey), eq(3))
    }

    @Test
    fun `attestExisting UNAVAILABLE to 503002`() {
        stubInstallToken()
        whenever(attestGuard.parseProofInput(any(), anyOrNull(), anyOrNull())).thenReturn(ProofBundle(110, "k", byteArrayOf(1), "ch"))
        whenever(attestGuard.verifyProof(any(), anyOrNull(), anyOrNull()))
            .thenReturn(Verification.Unavailable(AttestGuard.Reason.PERMITS_EXHAUSTED))
        whenever(attestGuard.isAttestationEnabled(any())).thenReturn(true)
        val e = assertThrows<ApiError> { fetcher.attestExisting(dfe, attestInput()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }
}
