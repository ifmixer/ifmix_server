package com.ifmix.core.api.infra.attest

import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * createInstall / attestExisting / recoverInstall 的服务端 attestation 门禁（规格 §5.2 组件表 AttestGuard 行）。
 *
 * 三个职责清楚的方法（各自只有一套 INVALID 语义；外部编排顺序由 Fetcher 保证，规格 §4.6 伪代码）：
 * - [verifyProof]：纯技术验证（challenge 校验 → AppAttestVerifier），**不套 mode**。
 * - [decideCreateInstall]：createInstall 专用，套 §4.3 判定矩阵；抛出的 403001/503002 路径不消耗日额度（Fetcher 顺序保证）。
 * - [consume]：一次性消费 challenge（AttestReplayGuard.markUsed(SHA256(challengeStr), 360s)；
 *   REPLAY → 403001(replay)，DEGRADED → 放行 + 节流 ERROR `attest.redis_degraded`（guard 内打））。
 *
 * 不写业务表；绑定在 [com.ifmix.core.api.modules.install.handler.InstallAggHandler] 事务内完成（§5.3）。
 *
 * 配置与开关（§4.1，env 接线在此）：
 * - 全局 kill switch：env `APP_ATTEST_GLOBAL_ENABLED`（默认 false）。OFF / project 未配置 → [Verification.Disabled]
 *   （decide 直接放行，attestationStatus=30）。
 * - challenge secret：env `APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，各为 32 字节 base64）；
 *   解析成 [AttestChallengeCodec]。全局开关开但 secret 缺失/非法 → ENFORCE 下按「配置无效」fail-closed（503002）。
 * - per-project 配置经 [ProjectServerConfigFacade.findAttestConfig]（进程内缓存，重启生效）读取；
 *   ENFORCE 且配置无效（§4.1 校验口径）→ [Verification.Unavailable]（CONFIG_INVALID）+ 节流日志
 *   `attest.config_invalid`（projectId+configHash；首条 + 每分钟一条，进程内存；修复后 INFO `attest.config_recovered`）。
 */
@Component
class AttestGuard(
    private val serverConfigFacade: ProjectServerConfigFacade,
    /** 共享的 App Attest 验签器（信号量/信任锚进程内单例）；recoverInstall 路径的 assertion 验证经此暴露。 */
    val appAttestVerifier: AppAttestVerifier,
    private val replayGuard: AttestReplayGuard,
    @param:Value("\${APP_ATTEST_GLOBAL_ENABLED:false}")
    var globalEnabled: Boolean = false,
    /**
     * env `APP_ATTEST_CHALLENGE_SECRET` 原文（`current,previous`，32 字节 base64）。
     * 缺失/非法 → [challengeCodec] 为 null：challenge 无法校验/签发（§4.1「缺 secret 而全局开关开着 → 配置无效」）。
     */
    @param:Value("\${APP_ATTEST_CHALLENGE_SECRET:}")
    private var challengeSecretRaw: String = "",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 解析后的 challenge codec；null = secret 未配置或非法。 */
    private var _challengeCodec: AttestChallengeCodec? = null
    val challengeCodec: AttestChallengeCodec?
        get() = _challengeCodec

    init {
        rebuildCodec()
    }

    /** 测试/运维入口：重设 env 解析后的 secret 原文（当前为重启生效的约定，与限流配置一致）。 */
    fun setChallengeSecretRaw(raw: String) {
        challengeSecretRaw = raw
        rebuildCodec()
    }

    private fun rebuildCodec() {
        if (challengeSecretRaw.isBlank()) {
            _challengeCodec = null
            return
        }
        _challengeCodec = runCatching {
            val secrets = challengeSecretRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(2)
                .map { Base64.getDecoder().decode(it) }
            AttestChallengeCodec(secrets)
        }.onFailure {
            log.warn("event=attest.secret_invalid — APP_ATTEST_CHALLENGE_SECRET 非法（应为 base64 current,previous），按未配置处理")
        }.getOrNull()
    }

    // ===== 常量：provider 码（§4.2）=====

    companion object {
        /** 110 = APP_ATTEST（iOS，一期 1a）；120 = PLAY_INTEGRITY（Android，1b，本期 NotEvaluated）。 */
        const val PROVIDER_IOS = 110
        const val PROVIDER_ANDROID = 120

        /** 一次性消费 TTL（§3.1-c：SET attest:used:{SHA256(challenge)} 1 NX EX 360）。 */
        const val REPLAY_GUARD_TTL_SEC = 360L

        /** 日志/告警节流间隔（§4.1：首条 + 之后每分钟一条）。 */
        private const val RE_LOG_INTERVAL_MS = 60_000L

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 日志脱敏（§5.5：不打印 keyId 原文，打 SHA-256 前 8 hex）。 */
        fun keyIdHash8(keyId: String?): String? = keyId?.let { sha256Hex(it).take(8) }
    }

    // ===== 类型 =====

    /** 解析+组合校验后的 proof 载体（provider 110：subject=keyId、bytes=attestationObject、challenge）。 */
    data class ProofBundle(
        val provider: Int,
        val subject: String?,
        val bytes: ByteArray?,
        val challenge: String?,
    )

    /**
     * 解析 CreateInstallInput 的 proof / proofStatus（§5.1 组合表 + §5.7 输入上限，400000 路径由调用方前置调用）。
     * 返回 null = 未带 proof（含未启用/设备不支持/旧版本）。
     */
    fun parseProofInput(input: Map<String, Any?>?, proofStatus: Int?): ProofBundle? {
        @Suppress("UNCHECKED_CAST")
        val proof = input?.get("proof") as? Map<String, Any?>
        if (proof != null && proofStatus != null) {
            throw guardInvalid("proof and proofStatus are mutually exclusive")
        }
        if (proofStatus != null && proofStatus != 20) {
            throw guardInvalid("invalid proofStatus: $proofStatus (expected null or 20)")
        }
        if (proof == null) return null

        val provider = (proof["provider"] as? Number)?.toInt()
            ?: throw guardInvalid("proof.provider is required")
        return when (provider) {
            PROVIDER_IOS -> {
                @Suppress("UNCHECKED_CAST")
                val ios = proof["appAttest"] as? Map<String, Any?>
                    ?: throw guardInvalid("provider 110 requires appAttest sub-object (proof mismatch)")
                val keyId = ios["keyId"] as? String ?: throw guardInvalid("appAttest.keyId is required")
                val attestationObject = ios["attestationObject"] as? String
                    ?: throw guardInvalid("appAttest.attestationObject is required")
                val challenge = ios["challenge"] as? String ?: throw guardInvalid("appAttest.challenge is required")
                checkLength("keyId", keyId, 64)
                checkLength("attestationObject", attestationObject, 16 * 1024)
                checkLength("challenge", challenge, 128)
                ProofBundle(PROVIDER_IOS, keyId, decodeBase64Flexible(attestationObject, "attestationObject"), challenge)
            }
            PROVIDER_ANDROID -> {
                // 1b：integrityToken / nonce 上限同 §5.7；一期只保留 400 校验，验证归 NotEvaluated
                @Suppress("UNCHECKED_CAST")
                val android = proof["playIntegrity"] as? Map<String, Any?>
                    ?: throw guardInvalid("provider 120 requires playIntegrity sub-object (proof mismatch)")
                val token = android["integrityToken"] as? String
                    ?: throw guardInvalid("playIntegrity.integrityToken is required")
                val nonce = android["nonce"] as? String ?: throw guardInvalid("playIntegrity.nonce is required")
                checkLength("integrityToken", token, 16 * 1024)
                checkLength("nonce", nonce, 64)
                ProofBundle(PROVIDER_ANDROID, null, decodeBase64Flexible(token, "integrityToken"), null)
            }
            else -> throw guardInvalid("unknown provider: $provider")
        }
    }

    /** proofStatus 组合校验：客户端是否声明 proof 临时不可用（§4.3 行 6）。 */
    fun isProofDeclaredUnavailable(proofStatus: Int?) = proofStatus == 20

    /**
     * 纯技术验证结果（不套 mode）。
     * - [Disabled]：全局开关 OFF / project 未配置 / mode=OFF → 「未启用」，decide 直接放行（attestationStatus=30）。
     * - [NoProof]：已启用但请求未带 proof（未声明 UNAVAILABLE）。ENFORCE → 403001；OBSERVE/OFF → 放行（30）。
     * - [Unavailable]：没法给技术结论（配置无效 fail-closed / 信号量耗尽 / secret 缺失 / 客户端声明 UNAVAILABLE / 1b Google 依赖）。
     *   ENFORCE → 503002；OBSERVE → 放行（30）。
     * - [NotEvaluated]：实现了但本期未做（provider 120）；OBSERVE 放行不持久化（30），ENFORCE → 503002。
     * - [Invalid] / [Valid]：有明确技术结论。
     */
    sealed interface Verification {
        @Suppress("ArrayInDataClass")
        data class Valid(
            val proof: VerifiedProof,
            /** 原始 challengeStr（一次性消费用）；非 null。 */
            val challenge: String,
        ) : Verification

        data class Invalid(val reason: Reason) : Verification
        data class Unavailable(val reason: Reason) : Verification
        data object Disabled : Verification
        data object NoProof : Verification
        data object NotEvaluated : Verification
    }

    /**
     * 解析后的 proof（§5.2 VerifiedProof）：createInstall 时 receipt 恒 null（core-job 回填任务写入，§5.8）。
     * signals/evidence 为固定键集合（§5.7）；evidence 不含原始 token。
     */
    @Suppress("ArrayInDataClass")
    data class VerifiedProof(
        val provider: Int,
        /** iOS keyId（subject 唯一约束用）；Android 1b 为 null。 */
        val subject: String?,
        val publicKey: ByteArray?,
        val receipt: ByteArray? = null,
        /** 原始 attestationObject（入库 attestation_object 列，core-job 回填 receipt 后清空，§5.4/§5.8）。 */
        val attestationObject: ByteArray? = null,
        val signals: Map<String, Any?>,
        val evidence: Map<String, Any?>,
    )

    /** reason 取值对齐规格 §5.5 日志 reason 枚举（有限集合，不含自由文本）。 */
    enum class Reason(val code: String) {
        CHALLENGE_INVALID("challenge_invalid"),
        CHALLENGE_EXPIRED("challenge_expired"),
        CHALLENGE_MISSING("challenge_missing"),
        REPLAY("replay"),
        KEY_REUSED("key_reused"),
        PROVIDER_NOT_CONFIGURED("provider_not_configured"),
        PROVIDER_NOT_IMPLEMENTED("provider_not_implemented"),
        CONFIG_INVALID("config_invalid"),
        SECRET_MISSING("secret_missing"),
        CLIENT_UNAVAILABLE("client_unavailable"),
        PARSE_FAILED("parse_failed"),
        CHAIN_INVALID("chain_invalid"),
        NONCE_MISMATCH("nonce_mismatch"),
        RP_MISMATCH("rp_mismatch"),
        COUNTER("counter"),
        KEY_MISMATCH("key_mismatch"),
        AAGUID("aaguid"),
        SIGNATURE_INVALID("signature_invalid"),
        CLIENT_DATA_INVALID("client_data_invalid"),
        ATTESTATION_INVALID("attestation_invalid"),
        UNSUPPORTED_KEY("unsupported_key"),
        VERIFICATION_FAILED("verification_failed"),
        PERMITS_EXHAUSTED("permits_exhausted"),
    }

    /**
     * createInstall 判定结果（§4.3 矩阵 + §4.4 attestationStatus 映射）：
     * - [VERIFIED]：VALID 且（OBSERVE/ENFORCE 下通过）→ 进 attested 日桶、事务内绑定 → attestationStatus=10（绑定成功后）。
     * - [NOT_PERSISTED]：带了 proof 但没绑定（OBSERVE 下 INVALID 等）→ unverified 日桶，attestationStatus=20。
     * - [NOT_ATTEMPTED]：没带 proof / 服务端未校验 / 声明 UNAVAILABLE（OBSERVE 放行）→ unverified 日桶，attestationStatus=30。
     */
    enum class CreateInstallDecision {
        VERIFIED,
        NOT_PERSISTED,
        NOT_ATTEMPTED,
    }

    /** 组合/输入上限校验失败统一抛 ApiError(400000)；[guardInvalid] 构造它（§5.1 组合表、§5.7 输入上限）。 */

    /**
     * Guard 判定失败：携带该抛的 [ApiError]（403001 缺失/无效/replay/key 已绑定、400000 组合非法、503002 配置无效/依赖故障）。
     * 事务阶段（key_reused、DB 错误）不抛本类型——由 handler 直接抛 [ApiError]（§4.6：额度已扣不退）。
     * cause 链挂 [apiError]：GraphQLExceptionHandler 沿 cause 找 ApiError（extensions.retryAfterSec 等随 ApiError 透传）。
     */
    class GuardError(val apiError: ApiError) : RuntimeException(apiError.message, apiError)

    // ===== 三方法 =====

    /**
     * 纯技术验证（规格 §5.2，不套 mode；mode 在 [decideCreateInstall] / attestExisting 映射里做）。
     *
     * 配置读取：[ProjectServerConfigFacade.findAttestConfig]（缓存）+ 全局开关 + challenge secret（env，本类持有）。
     * 全局开关 OFF / project 未配置 / mode=OFF → [Verification.Disabled]。
     * ENFORCE 且（配置无效 or secret 缺失）→ [Verification.Unavailable](CONFIG_INVALID) + 节流 `attest.config_invalid`。
     * 其余路径：provider 110 → challenge 校验（AttestChallengeCodec）→ AppAttestVerifier.verifyAttestation（信号量在 verifier 内）
     * → VALID(reason 枚举/公钥/signals/evidence) 或 INVALID(reason)；provider 120 → [Verification.NotEvaluated]（1b）。
     */
    fun verifyProof(ctx: ActionContext, bundle: ProofBundle?, proofStatus: Int?): Verification {
        val projectId = ctx.mustGetProjectId()
        val t0 = System.currentTimeMillis()
        val config = serverConfigFacade.findAttestConfig(projectId)

        if (!globalEnabled || config == null || config.mode == AttestMode.OFF) {
            logAttest(projectId, config?.mode?.name ?: "OFF", bundle?.provider ?: 0, proofStatus, "missing", null, 0L, t0, bundle?.subject)
            return Verification.Disabled
        }

        // §4.1 fail-closed：ENFORCE 有效条件 = isValidForEnforce()（至少一个 provider 且全部可解析）+ secret 可用。
        if (config.mode == AttestMode.ENFORCE && (!config.isValidForEnforce() || challengeCodec == null)) {
            logConfigInvalid(projectId, config, challengeCodec == null)
            logAttest(projectId, "ENFORCE", bundle?.provider ?: 0, proofStatus, "unavailable", "config_invalid", 0L, t0, bundle?.subject)
            return Verification.Unavailable(Reason.CONFIG_INVALID)
        }

        // 配置从无效变有效：打一条 recovered INFO（节流状态记录）
        markConfigRecoveredIfThrottled(projectId)

        val tVerify = System.currentTimeMillis()
        val verification = verifyBundle(config, bundle, proofStatus, projectId)
        val tEnd = System.currentTimeMillis()
        val verifyMs = tEnd - tVerify
        val totalMs = tEnd - t0

        val (result, reason) = when (verification) {
            is Verification.Valid -> "valid" to null
            is Verification.Invalid -> "invalid" to verification.reason.code
            is Verification.Unavailable -> "unavailable" to verification.reason.code
            is Verification.NoProof -> "missing" to null
            is Verification.NotEvaluated -> "not_evaluated" to null
            is Verification.Disabled -> "missing" to null
        }
        logAttest(projectId, config.mode.name, bundle?.provider ?: 0, proofStatus, result, reason, verifyMs, t0, bundle?.subject)
        return verification
    }

    private fun verifyBundle(
        config: AttestConfig,
        bundle: ProofBundle?,
        proofStatus: Int?,
        projectId: String,
    ): Verification {
        if (bundle == null) {
            return if (isProofDeclaredUnavailable(proofStatus)) {
                // §4.3 行 6：客户端已尝试但临时故障。ENFORCE → decide 判 503002；OBSERVE 放行。
                Verification.Unavailable(Reason.CLIENT_UNAVAILABLE)
            } else {
                Verification.NoProof
            }
        }
        if (bundle.provider == PROVIDER_ANDROID) {
            // 1b：PlayIntegrityVerifier（decode+校验+token 去重）未实现，本期 NotEvaluated。
            return Verification.NotEvaluated
        }
        // provider 110（1a）
        val ios = config.ios ?: return Verification.Invalid(Reason.PROVIDER_NOT_CONFIGURED)
        val challenge = bundle.challenge ?: return Verification.Invalid(Reason.CHALLENGE_MISSING)
        val codec = challengeCodec
            ?: return Verification.Unavailable(Reason.SECRET_MISSING)
        when (val c = codec.verify(projectId, challenge)) {
            is AttestChallengeCodec.Verification.Invalid ->
                return Verification.Invalid(mapChallengeReason(c.reason))
            else -> Unit
        }
        val attestationObject = bundle.bytes ?: return Verification.Invalid(Reason.PARSE_FAILED)
        val keyId = bundle.subject ?: return Verification.Invalid(Reason.KEY_MISMATCH)
        return when (val v = appAttestVerifier.verifyAttestation(
            keyId = keyId,
            attestationObject = attestationObject,
            challenge = challenge,
            teamId = ios.teamId,
            bundleId = ios.bundleId,
            production = ios.production,
        )) {
            is AppAttestVerification.AttestationSuccess -> Verification.Valid(
                proof = VerifiedProof(
                    provider = PROVIDER_IOS,
                    subject = keyId,
                    publicKey = v.publicKey,
                    attestationObject = attestationObject,
                    // §5.7 signals 固定键：iOS = env（fraudMetric 由 core-job 后台写入）
                    signals = mapOf("env" to if (ios.production) "production" else "development"),
                    // evidence 不含原始 token；这里只留 provider 摘要（原始 attestationObject 入库 attestation_object 列，core-job 回填后清）
                    evidence = mapOf("provider" to PROVIDER_IOS),
                ),
                challenge = challenge,
            )
            is AppAttestVerification.Invalid -> Verification.Invalid(mapVerifierReason(v.reason))
            is AppAttestVerification.Unavailable -> Verification.Unavailable(Reason.PERMITS_EXHAUSTED)
            else -> Verification.Invalid(Reason.VERIFICATION_FAILED)
        }
    }

    /**
     * createInstall 判定（§4.3 矩阵 + §4.4 错误码）：
     *
     * | verification | OFF/未配置 | OBSERVE | ENFORCE |
     * |---|---|---|---|
     * | Disabled | 放行 NOT_ATTEMPTED | 放行 NOT_ATTEMPTED | 不可达（verifyProof 已保证） |
     * | NoProof | 放行 NOT_ATTEMPTED | 放行 NOT_ATTEMPTED | 403001 |
     * | Invalid | — | 放行 NOT_PERSISTED | 403001 |
     * | Unavailable | — | 放行 NOT_ATTEMPTED | 503002 |
     * | NotEvaluated | — | 放行 NOT_ATTEMPTED | 503002 |
     * | Valid | 放行 VERIFIED | VERIFIED | VERIFIED |
     *
     * 被调前提（Fetcher 保证顺序）：入口分钟窗口、§5.1 组合校验均已在 Guard 之前；本方法抛出的
     * 403001/503002 路径**不消耗日额度**（日窗口在 decide 之后，§4.6）。
     */
    fun decideCreateInstall(verification: Verification, mode: AttestMode?): CreateInstallDecision = when (mode) {
        null, AttestMode.OFF -> when (verification) {
            is Verification.Valid -> CreateInstallDecision.VERIFIED
            else -> CreateInstallDecision.NOT_ATTEMPTED
        }
        AttestMode.OBSERVE -> when (verification) {
            is Verification.Valid -> CreateInstallDecision.VERIFIED
            is Verification.Invalid -> CreateInstallDecision.NOT_PERSISTED
            else -> CreateInstallDecision.NOT_ATTEMPTED
        }
        AttestMode.ENFORCE -> when (verification) {
            is Verification.Valid -> CreateInstallDecision.VERIFIED
            is Verification.Invalid -> throw GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "attestation failed: ${verification.reason.code}"))
            is Verification.Unavailable -> throw GuardError(ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attestation temporarily unavailable: ${verification.reason.code}"))
            is Verification.NotEvaluated -> throw GuardError(ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attestation provider not available in this phase"))
            is Verification.NoProof -> throw GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "attestation proof required"))
            is Verification.Disabled -> CreateInstallDecision.NOT_ATTEMPTED // 防御性不可达
        }
    }

    /**
     * 一次性消费 challenge（§3.1-c，§4.6：日窗口通过之后执行）：
     * AttestReplayGuard.markUsed(`attest:used:{SHA256(challengeStr)}`, 360s) →
     * - FIRST / DEGRADED → 放行（DEGRADED 由 guard 打节流 ERROR `attest.redis_degraded`，可用性优先跳过重放保护）；
     * - REPLAY → 抛 [GuardError]（403001 replay，客户端转 recover / attestExisting §6.7 outcome 表）。
     * 未带 challenge（NoProof / Disabled / 非 iOS 1b）→ no-op。
     */
    fun consume(verification: Verification) {
        val challenge = (verification as? Verification.Valid)?.challenge ?: return
        consumeChallenge(challenge)
    }

    /** 独立的 challenge 一次性消费入口（recoverInstall 路径 §3.3-d：assertion 校验后、事务内条件更新前）。 */
    fun consumeChallenge(challenge: String?) {
        if (challenge.isNullOrBlank()) return
        when (replayGuard.markUsed("attest:used:${sha256Hex(challenge)}", REPLAY_GUARD_TTL_SEC)) {
            AttestReplayGuard.MarkResult.FIRST,
            AttestReplayGuard.MarkResult.DEGRADED -> Unit
            AttestReplayGuard.MarkResult.REPLAY ->
                throw GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "attestation challenge already consumed (replay)"))
        }
    }

    // ===== recover / challenge / attestExisting 辅助（§3.3、§5.1、§6.7）=====

    /**
     * recoverInstall 的 challenge 校验（§3.3-a，与 mode/全局开关无关——决策 9）：
     * secret 缺失 → [GuardError]（503002）；challenge 无效/过期 → [GuardError]（403001）。
     */
    fun checkRecoverChallenge(projectId: String, challenge: String) {
        val codec = challengeCodec
            ?: throw GuardError(ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attest challenge secret not configured"))
        when (val c = codec.verify(projectId, challenge)) {
            is AttestChallengeCodec.Verification.Invalid ->
                throw GuardError(ApiError(ErrorCode.ATTESTATION_FAILED, "invalid recover challenge: ${c.reason.code}"))
            else -> Unit
        }
    }

    /** 签发一次性 challenge（§5.1，纯计算不碰 Redis）。secret 缺失（全局开关开时按配置无效）→ [GuardError]（503002）。 */
    fun issueChallenge(projectId: String): String {
        val codec = challengeCodec
            ?: throw GuardError(ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attest challenge secret not configured"))
        return codec.issue(projectId)
    }

    /**
     * challenge 接口的 enabled 判定（§5.1 v5 注）：全局开关 && 配置可解析 && mode!=OFF &&
     * 报 x-client-platform header 对应平台子对象存在。纯计算。
     */
    fun isChallengeEnabled(projectId: String, clientPlatform: ClientPlatform?): Boolean {
        if (!globalEnabled) return false
        val config = serverConfigFacade.findAttestConfig(projectId) ?: return false
        if (config.mode == AttestMode.OFF || !config.isValid) return false
        return when (clientPlatform) {
            ClientPlatform.IOS -> config.ios != null
            ClientPlatform.ANDROID -> config.android != null
            else -> false
        }
    }

    /**
     * 交叉核对（§5.9）：VALID 且 storeType 与 provider 期望不一致 → 只打 `store_mismatch` 日志，不拒绝。
     * 1a：provider 110 期望 10（APP_STORE）；1b：provider 120 期望 20（GOOGLE_PLAY）。
     */
    fun logStoreMismatch(projectId: String, provider: Int, storeType: Int?) {
        log.info("event=store_mismatch pid={} provider={} storeType={} — 客户端自报值仅统计，不拒绝请求", projectId, provider, storeType)
    }

    /** attestExisting 的服务端启用判定（§6.7 第 3 步）：OFF / 未配置 / 全局 OFF → 返回 attestationStatus=30。 */
    fun isAttestationEnabled(projectId: String): Boolean {
        if (!globalEnabled) return false
        val config = serverConfigFacade.findAttestConfig(projectId) ?: return false
        return config.mode != AttestMode.OFF
    }

    // ===== 配置无效日志节流（§4.1：projectId+configHash 首条 + 每分钟一条；修复后一条 INFO）=====

    private data class ConfigThrottle(var lastLoggedAtMs: Long)

    private val configThrottle = ConcurrentHashMap<String, ConfigThrottle>()

    private fun logConfigInvalid(projectId: String, config: AttestConfig, secretMissing: Boolean) {
        val now = System.currentTimeMillis()
        var shouldLog = false
        configThrottle.compute(projectId) { _, existing ->
            if (existing == null || now - existing.lastLoggedAtMs >= RE_LOG_INTERVAL_MS) {
                shouldLog = true
                ConfigThrottle(now)
            } else {
                existing
            }
        }
        if (shouldLog) {
            val hash = sha256Hex("${config.mode}|${config.ios?.teamId}|${config.ios?.bundleId}|${config.android?.packageName}|${config.problems.joinToString(";")}")
            log.error(
                "event=attest.config_invalid pid={} desiredMode={} missing={} configHash={} secretConfigured={} — fail-closed, createInstall/recoverInstall/createAttestChallenge 503002",
                projectId, config.mode.name, config.problems.ifEmpty { listOf(if (secretMissing) "challenge_secret" else "enforce_integrity") },
                hash.take(16), !secretMissing,
            )
        }
    }

    private fun markConfigRecoveredIfThrottled(projectId: String) {
        if (configThrottle.remove(projectId) != null) {
            log.info("event=attest.config_recovered pid={}", projectId)
        }
    }

    private fun logAttest(
        projectId: String,
        mode: String,
        provider: Int,
        proofStatus: Int?,
        result: String,
        reason: String?,
        verifyMs: Long,
        t0: Long,
        keyId: String?,
    ) {
        // §5.5：event=install.attest mode provider proofStatus result reason verifyMs totalMs；不打印 proof 原文，keyId 打 SHA-256 前 8 hex
        log.info(
            "event=install.attest mode={} provider={} proofStatus={} result={} reason={} verifyMs={} totalMs={} keyId8={}",
            mode, provider, proofStatus, result, reason, verifyMs, System.currentTimeMillis() - t0, keyIdHash8(keyId),
        )
    }

    private fun checkLength(field: String, value: String, max: Int) {
        if (value.length > max) throw guardInvalid("$field exceeds ${max} chars")
    }

    private fun decodeBase64Flexible(raw: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(raw)
    } catch (_: IllegalArgumentException) {
        try {
            Base64.getUrlDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw guardInvalid("$field is not valid base64")
        }
    }

    private fun mapChallengeReason(r: AttestChallengeCodec.Reason): Reason = when (r) {
        AttestChallengeCodec.Reason.CHALLENGE_INVALID -> Reason.CHALLENGE_INVALID
        AttestChallengeCodec.Reason.CHALLENGE_EXPIRED -> Reason.CHALLENGE_EXPIRED
    }

    /** 组合/输入上限 400000 构造点（§5.1 表 + §5.7 上限；与 GuardError 的 403/503 区分）。 */
    private fun guardInvalid(message: String): ApiError = ApiError(ErrorCode.INVALID_REQUEST, message)

    private fun mapVerifierReason(r: AppAttestVerification.Reason): Reason = when (r) {
        AppAttestVerification.Reason.PARSE_FAILED -> Reason.PARSE_FAILED
        AppAttestVerification.Reason.CHAIN_INVALID -> Reason.CHAIN_INVALID
        AppAttestVerification.Reason.NONCE_MISMATCH -> Reason.NONCE_MISMATCH
        AppAttestVerification.Reason.RP_MISMATCH -> Reason.RP_MISMATCH
        AppAttestVerification.Reason.COUNTER -> Reason.COUNTER
        AppAttestVerification.Reason.KEY_MISMATCH -> Reason.KEY_MISMATCH
        AppAttestVerification.Reason.AAGUID -> Reason.AAGUID
        AppAttestVerification.Reason.SIGNATURE_INVALID -> Reason.SIGNATURE_INVALID
        AppAttestVerification.Reason.CLIENT_DATA_INVALID -> Reason.CLIENT_DATA_INVALID
        AppAttestVerification.Reason.ATTESTATION_INVALID -> Reason.ATTESTATION_INVALID
        AppAttestVerification.Reason.UNSUPPORTED_KEY -> Reason.UNSUPPORTED_KEY
        AppAttestVerification.Reason.VERIFICATION_FAILED -> Reason.VERIFICATION_FAILED
    }
}
