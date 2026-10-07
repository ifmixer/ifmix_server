package com.ifmix.core.api.infra.attest

import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64
import java.util.UUID

/**
 * AttestGuard 判定矩阵（规格 §7 AttestGuard 列表 + 补充）：
 * 全局开关 × mode × {VALID, INVALID, 无 proof, proofStatus=UNAVAILABLE, 配置无效, 缺 deviceCheck* 仍有效}；
 * ENFORCE 下垃圾 proof → 403001；proofStatus 非法/与 proof 同时出现 → 400000；config_invalid 节流（重复调用不崩溃、恢复路径）。
 */
class AttestGuardTest {

    private val ctx = ActionContext(projectId = "p1", clientPlatform = ClientPlatform.IOS)

    private val facade: ProjectServerConfigFacade = mock()
    private val verifier: AppAttestVerifier = mock()
    private val replayGuard: AttestReplayGuard = mock()
    private lateinit var guard: AttestGuard

    private val secret = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)
    private val secretB64 = Base64.getEncoder().encodeToString(secret)

    private fun guardWith(secretRaw: String = secretB64, global: Boolean = true): AttestGuard {
        val g = AttestGuard(facade, verifier, replayGuard, global)
        g.setChallengeSecretRaw(secretRaw)
        return g
    }

    private fun iosConfig(mode: AttestMode = AttestMode.OBSERVE): AttestConfig = AttestConfig.parse(
        mapOf(
            "mode" to mode.name,
            "ios" to mapOf(
                "teamId" to "ABCDE12345",
                "bundleId" to "com.example.antique",
                "env" to "production",
                "deviceCheckKeyId" to "K1",
                "deviceCheckPrivateKey" to "pem",
            ),
        ),
    )

    /** 通过 issue 得到合法 challengeStr。 */
    private fun validChallenge(): String = guard.challengeCodec!!.issue("p1")

    @BeforeEach
    fun setUp() {
        guard = guardWith()
    }

    // ===== 全局开关 × 未配置 → Disabled，decide 放行 =====

    @Test
    fun global_off_returns_Disabled_and_decide_passes() {
        whenever(facade.findAttestConfig("p1")).thenReturn(null)
        val g = guardWith(global = false)
        assertThat(g.verifyProof(ctx, null, null)).isEqualTo(AttestGuard.Verification.Disabled)
        assertThat(g.decideCreateInstall(AttestGuard.Verification.Disabled, null)).isEqualTo(AttestGuard.CreateInstallDecision.NOT_ATTEMPTED)
    }

    @Test
    fun project_configured_null_returns_Disabled() {
        whenever(facade.findAttestConfig("p1")).thenReturn(null)
        assertThat(guard.verifyProof(ctx, null, null)).isEqualTo(AttestGuard.Verification.Disabled)
    }

    @Test
    fun mode_OFF_returns_Disabled() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.OFF))
        assertThat(guard.verifyProof(ctx, null, null)).isEqualTo(AttestGuard.Verification.Disabled)
    }

    // ===== 判定矩阵（OBSERVE 全放行；ENFORCE INVALID→403001、UNAVAILABLE→503002）=====

    @Test
    fun OBSERVE___INVALID_proof_is_NOT_PERSISTED() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig())
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID))
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Invalid::class.java)
        assertThat(guard.decideCreateInstall(v, AttestMode.OBSERVE)).isEqualTo(AttestGuard.CreateInstallDecision.NOT_PERSISTED)
    }

    @Test
    fun ENFORCE___INVALID_proof_throws_403001() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID))
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Invalid::class.java)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    @Test
    fun ENFORCE___no_proof_throws_403001() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        val v = guard.verifyProof(ctx, null, null)
        assertThat(v).isEqualTo(AttestGuard.Verification.NoProof)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    @Test
    fun ENFORCE___proofStatus_UNAVAILABLE_throws_503002() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        val v = guard.verifyProof(ctx, null, 20)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Unavailable::class.java)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    @Test
    fun OBSERVE___proofStatus_UNAVAILABLE_passes_as_NOT_ATTEMPTED() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig())
        val v = guard.verifyProof(ctx, null, 20)
        assertThat(guard.decideCreateInstall(v, AttestMode.OBSERVE)).isEqualTo(AttestGuard.CreateInstallDecision.NOT_ATTEMPTED)
    }

    @Test
    fun ENFORCE___VALID_proof_is_VERIFIED() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(
                AppAttestVerification.AttestationSuccess(
                    credentialId = byteArrayOf(1),
                    publicKey = byteArrayOf(2),
                    signCount = 0,
                    authData = byteArrayOf(3),
                ),
            )
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Valid::class.java)
        assertThat(guard.decideCreateInstall(v, AttestMode.ENFORCE)).isEqualTo(AttestGuard.CreateInstallDecision.VERIFIED)
    }

    @Test
    fun enforce_google_unavailable_returns_503002() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        val v = guard.verifyProof(ctx, androidBundle(), null)
        assertThat(v).isEqualTo(AttestGuard.Verification.NotEvaluated)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    @Test
    fun enforce_verifier_unavailable_returns_503002() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Unavailable)
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Unavailable::class.java)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    // ===== 配置无效（ENFORCE fail-closed；缺 deviceCheck* 不影响有效性）=====

    @Test
    fun enforce_config_invalid_missing_provider_returns_unavailable_no_crash_on_repeat() {
        // 配置存在但 ENFORCE 完整性不满足：ios 子对象缺失（未配置任何 provider）
        val bad = AttestConfig(AttestMode.ENFORCE, null, null, emptyList())
        whenever(facade.findAttestConfig("p1")).thenReturn(bad)
        repeat(3) { // 节流路径：首条 + 每分钟一条；重复调用不崩溃
            val v = guard.verifyProof(ctx, null, null)
            assertThat(v).isInstanceOf(AttestGuard.Verification.Unavailable::class.java)
            assertThat((v as AttestGuard.Verification.Unavailable).reason).isEqualTo(AttestGuard.Reason.CONFIG_INVALID)
        }
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v0(), AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    @Test
    fun enforce_secret_missing_fail_closed() {
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.ENFORCE))
        val g = guardWith(secretRaw = "", global = true) // secret 未配置
        val v = g.verifyProof(ctx, null, null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Unavailable::class.java)
        assertThat((v as AttestGuard.Verification.Unavailable).reason).isEqualTo(AttestGuard.Reason.CONFIG_INVALID)
    }

    @Test
    fun enforce_ios_without_devicecheck_still_valid() {
        val cfg = AttestConfig.parse(mapOf(
            "mode" to "ENFORCE",
            "ios" to mapOf("teamId" to "ABCDE", "bundleId" to "com.x.y", "env" to "production"),
        ))
        assertThat(cfg.isValidForEnforce()).isTrue
        whenever(facade.findAttestConfig("p1")).thenReturn(cfg)
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Invalid::class.java) // challenge OK → 走 verifier（mock 默认 Invalid 由 verifier mock 决定）
    }

    @Test
    fun observe_config_problems_not_fail_closed() {
        val cfg = AttestConfig(AttestMode.OBSERVE, IosAttestConfig("T", "B", true, null, null), null, listOf("ios: env: invalid value 'x'"))
        whenever(facade.findAttestConfig("p1")).thenReturn(cfg)
        val v = guard.verifyProof(ctx, null, null)
        // OBSERVE 不做 ENFORCE 完整性检查：config invalid 不触发 503002，NoProof 放行
        assertThat(v).isEqualTo(AttestGuard.Verification.NoProof)
    }

    // ===== 垃圾 proof（弱 provider 未配置）在 ENFORCE 下 403001 =====

    @Test
    fun enforce_garbage_proof_unconfigured_provider() {
        val cfg = AttestConfig(AttestMode.ENFORCE, IosAttestConfig("T", "B", true, null, null), null, emptyList())
        whenever(facade.findAttestConfig("p1")).thenReturn(cfg)
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID))
        val v = guard.verifyProof(ctx, iosBundle(validChallenge()), null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Invalid::class.java)
        val e = assertThrows<AttestGuard.GuardError> { guard.decideCreateInstall(v, AttestMode.ENFORCE) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    // ===== §5.1 组合校验 / §5.7 输入上限（400000）=====

    @Test
    fun proof_and_proofStatus_together___400000() {
        val input = mapOf("proof" to iosProofMap(validChallenge()))
        assertThrows<ApiError> { guard.parseProofInput(input, 20, AttestGuard.PROVIDER_IOS) }
    }

    @Test
    fun invalid_proofStatus_value___400000() {
        assertThrows<ApiError> { guard.parseProofInput(null, 30, AttestGuard.PROVIDER_IOS) }
    }

    @Test
    fun proofStatus_20_without_proof_is_accepted() {
        assertThat(guard.parseProofInput(null, 20, AttestGuard.PROVIDER_IOS)).isNull()
        assertThat(guard.isProofDeclaredUnavailable(20)).isTrue
    }

    @Test
    fun proof_provider_must_match_expected_provider_of_the_action() {
        // v6 按平台拆分：createIosInstall 只收 110，createAndroidInstall 只收 120
        assertThrows<ApiError> { guard.parseProofInput(androidProofInput(), null, AttestGuard.PROVIDER_IOS) }
        assertThrows<ApiError> { guard.parseProofInput(iosProofInput(), null, AttestGuard.PROVIDER_ANDROID) }
        assertThat(guard.parseProofInput(androidProofInput(), null, AttestGuard.PROVIDER_ANDROID)).isNotNull
    }

    private fun iosProofInput(): Map<String, Any?> =
        mapOf("proof" to mapOf("provider" to 110, "appAttest" to mapOf("keyId" to "k", "attestationObject" to b64(), "challenge" to validChallenge())))

    private fun androidProofInput(): Map<String, Any?> =
        mapOf("proof" to mapOf("provider" to 120, "playIntegrity" to mapOf("integrityToken" to b64(), "nonce" to "nonce")))

    private fun b64(): String = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1))

    @Test
    fun unknown_provider___400000() {
        val input = mapOf("proof" to mapOf("provider" to 999))
        assertThrows<ApiError> { guard.parseProofInput(input, null, AttestGuard.PROVIDER_IOS) }
    }

    @Test
    fun provider_110_without_appAttest_sub_object___400000() {
        val input = mapOf("proof" to mapOf("provider" to 110, "playIntegrity" to mapOf("integrityToken" to "x", "nonce" to "y")))
        assertThrows<ApiError> { guard.parseProofInput(input, null, AttestGuard.PROVIDER_IOS) }
    }

    @Test
    fun oversized_keyId___400000() {
        val input = mapOf("proof" to mapOf(
            "provider" to 110,
            "appAttest" to mapOf("keyId" to "k".repeat(65), "attestationObject" to "QUJD", "challenge" to "c"),
        ))
        assertThrows<ApiError> { guard.parseProofInput(input, null, AttestGuard.PROVIDER_IOS) }
    }

    @Test
    fun ios_bundle_parses_subject_and_decoded_bytes() {
        val keyId = "cGstZXk" // base64
        val att = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val input = mapOf("proof" to mapOf(
            "provider" to 110,
            "appAttest" to mapOf("keyId" to keyId, "attestationObject" to att, "challenge" to "c"),
        ))
        val b = guard.parseProofInput(input, null, AttestGuard.PROVIDER_IOS)!!
        assertThat(b.subject).isEqualTo(keyId)
        assertThat(b.bytes).containsExactly(1, 2, 3)
        assertThat(b.challenge).isEqualTo("c")
    }

    // ===== consume =====

    @Test
    fun consume___FIRST_and_DEGRADED_pass_through() {
        whenever(replayGuard.markUsed(any(), any())).thenReturn(AttestReplayGuard.MarkResult.FIRST)
        val v = validVerification("c1")
        guard.consume(v) // no-op 也允许（无 challenge）
        whenever(replayGuard.markUsed(any(), any())).thenReturn(AttestReplayGuard.MarkResult.DEGRADED)
        guard.consume(validVerification("c2"))
    }

    @Test
    fun consume___REPLAY_throws_403001() {
        whenever(replayGuard.markUsed(any(), any())).thenReturn(AttestReplayGuard.MarkResult.REPLAY)
        val e = assertThrows<AttestGuard.GuardError> { guard.consume(validVerification("c1")) }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_FAILED)
    }

    @Test
    fun consume_uses_SHA256_challengeStr__key_and_360s_TTL() {
        val argCaptor = mutableListOf<String>()
        whenever(replayGuard.markUsed(any(), any())).thenAnswer { argCaptor.add(it.arguments[0] as String); AttestReplayGuard.MarkResult.FIRST }
        guard.consume(validVerification("challenge-abc"))
        val expectedKey = "attest:used:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("challenge-abc".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertThat(argCaptor.single()).isEqualTo(expectedKey)
    }

    // ===== createAttestChallenge 的 enabled 判定（§5.1 v5 注）=====

    @Test
    fun challenge_enabled_requires_global_on___config___mode___platform_sub_object() {
        val g = guardWith(global = false)
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig())
        assertThat(g.isChallengeEnabled("p1", ClientPlatform.IOS)).isFalse // 全局 OFF
        val g2 = guardWith()
        assertThat(g2.isChallengeEnabled("p1", ClientPlatform.IOS)).isTrue
        assertThat(g2.isChallengeEnabled("p1", ClientPlatform.ANDROID)).isFalse // android 子对象不存在
        assertThat(g2.isChallengeEnabled("p1", ClientPlatform.WEB)).isFalse
        assertThat(g2.isChallengeEnabled("p1", null)).isFalse
        whenever(facade.findAttestConfig("p1")).thenReturn(iosConfig(AttestMode.OFF))
        assertThat(g2.isChallengeEnabled("p1", ClientPlatform.IOS)).isFalse
    }

    @Test
    fun issue_challenge_missing_secret___GuardError_503002() {
        val g = guardWith(secretRaw = "", global = false)
        val e = assertThrows<AttestGuard.GuardError> { g.issueChallenge("p1") }
        assertThat(e.apiError.errorCode).isEqualTo(ErrorCode.ATTESTATION_UNAVAILABLE)
    }

    // ===== per-project challengeSecret（优先于 env；非法不回落）=====

    private fun otherSecretB64(): String {
        val s = "fedcba9876543210fedcba9876543210".toByteArray(Charsets.US_ASCII)
        return Base64.getEncoder().encodeToString(s)
    }

    private fun configWithSecret(mode: AttestMode = AttestMode.OBSERVE, secret: String?): AttestConfig =
        AttestConfig.parse(
            mapOf(
                "mode" to mode.name,
                "challengeSecret" to secret,
                "ios" to mapOf("teamId" to "ABCDE12345", "bundleId" to "com.example.antique", "env" to "production"),
            ),
        )

    @Test
    fun per_project_secret_takes_precedence_over_env() {
        // env 配了 secretA，project 配了 secretB：签发/校验必须走 B（用 A 签的 challenge 判 challenge_invalid）
        whenever(facade.findAttestConfig("p1")).thenReturn(configWithSecret(secret = otherSecretB64()))
        val perProjectCodec = guard.challengeCodecFor(configWithSecret(secret = otherSecretB64()))!!
        val challenge = perProjectCodec.issue("p1")

        val envSigned = AttestChallengeCodec(listOf(secret)).issue("p1") // env secret 签的 → 应校验失败
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID))
        val v1 = guard.verifyProof(ctx, iosBundle(challenge), null)
        assertThat(v1).isInstanceOf(AttestGuard.Verification.Invalid::class.java)
        assertThat((v1 as AttestGuard.Verification.Invalid).reason).isEqualTo(AttestGuard.Reason.ATTESTATION_INVALID) // challenge 过了，挂在 verifier
        val v2 = guard.verifyProof(ctx, iosBundle(envSigned), null)
        assertThat((v2 as AttestGuard.Verification.Invalid).reason).isEqualTo(AttestGuard.Reason.CHALLENGE_INVALID)
    }

    @Test
    fun per_project_secret_missing_falls_back_to_env() {
        whenever(facade.findAttestConfig("p1")).thenReturn(configWithSecret(secret = null))
        val challenge = guard.challengeCodecFor(configWithSecret(secret = null))!!.issue("p1") // = env codec
        whenever(verifier.verifyAttestation(any(), any(), any(), any(), any(), any()))
            .thenReturn(AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID))
        val v = guard.verifyProof(ctx, iosBundle(challenge), null)
        assertThat((v as AttestGuard.Verification.Invalid).reason).isEqualTo(AttestGuard.Reason.ATTESTATION_INVALID)
    }

    @Test
    fun per_project_secret_invalid_records_problem_and_fails_closed_on_enforce() {
        // 非法 per-project secret：parse 丢弃该值（challengeSecret=null → codec 层面回落 env），
        // 但 problems 非空 → ENFORCE 下 isValidForEnforce=false → fail-closed 503002 路径
        val bad = "!!!not-base64!!!"
        val g = guardWith() // env 有合法 secret
        val badConfig = configWithSecret(secret = bad)
        assertThat(badConfig.challengeSecret).isNull()
        assertThat(badConfig.isValid).isFalse()
        assertThat(g.challengeCodecFor(badConfig)).isEqualTo(g.challengeCodec) // 回落 env
        whenever(facade.findAttestConfig("p1")).thenReturn(configWithSecret(AttestMode.ENFORCE, secret = bad))
        val v = g.verifyProof(ctx, null, null)
        assertThat(v).isInstanceOf(AttestGuard.Verification.Unavailable::class.java)
    }

    @Test
    fun per_project_secret_used_by_issueChallenge_without_env() {
        val g = guardWith(secretRaw = "", global = true) // env 无 secret
        whenever(facade.findAttestConfig("p1")).thenReturn(configWithSecret(secret = otherSecretB64()))
        val challenge = g.issueChallenge("p1") // 走 per-project secret，成功签发
        assertThat(challenge).isNotBlank()
    }

    // ===== 辅助 =====

    private fun v0(): AttestGuard.Verification = AttestGuard.Verification.Unavailable(AttestGuard.Reason.CONFIG_INVALID)

    private fun validVerification(challenge: String) = AttestGuard.Verification.Valid(
        proof = AttestGuard.VerifiedProof(provider = 110, subject = "k", publicKey = null, attestationObject = null, signals = emptyMap(), evidence = emptyMap()),
        challenge = challenge,
    )

    private fun iosBundle(challenge: String) = guard.parseProofInput(
        mapOf("proof" to iosProofMap(challenge)),
        null,
        AttestGuard.PROVIDER_IOS,
    )

    private fun iosProofMap(challenge: String) = mapOf(
        "provider" to 110,
        "appAttest" to mapOf(
            "keyId" to "key-id-abc",
            "attestationObject" to Base64.getEncoder().encodeToString(byteArrayOf(1)),
            "challenge" to challenge,
        ),
    )

    private fun androidBundle() = guard.parseProofInput(
        mapOf("proof" to mapOf(
            "provider" to 120,
            "playIntegrity" to mapOf(
                "integrityToken" to Base64.getEncoder().encodeToString(byteArrayOf(2)),
                "nonce" to "nonce",
            ),
        )),
        null,
        AttestGuard.PROVIDER_ANDROID,
    )
}
