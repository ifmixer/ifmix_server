package com.ifmix.core.api.bff.graphql.customer.install

import com.ifmix.core.api.entity.install.AttestationStatuses
import com.ifmix.core.api.generated.types.AttestChallengeResult
import com.ifmix.core.api.generated.types.AttestExistingResult
import com.ifmix.core.api.generated.types.CreateInstallResult
import com.ifmix.core.api.generated.types.UpdateInstallResult
import com.ifmix.core.api.infra.attest.AttestChallengeCodec
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.attest.AppAttestVerification
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.install.InstallFacade
import com.netflix.graphql.dgs.DgsComponent
import org.slf4j.LoggerFactory
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.util.Base64

/**
 * Install mutation（规格 §5.1/§4.6/§6.7）。
 *
 * createInstall 新流程顺序（§4.6 伪代码，Guard 阶段 400/403/503 不扣日额度）：
 * 1. IP 短窗口 100/60s → 429000(retryAfterSec=窗口剩余)
 * 2. proof/proofStatus 组合校验（§5.1 表）→ 400000
 * 3. verifyProof → decideCreateInstall（ENFORCE+INVALID → 403001；ENFORCE+无 proof/UNAVAILABLE → 403001/503002；OBSERVE 全放行）
 * 4. 日窗口（VALID→attested 1000；否则 unverified 100）→ 429002(retryAfterSec=到 UTC 零点)
 * 5. consume（replay → 403001）
 * 6. globalTx { createInstallWithProof }（key_reused 在事务阶段，额度不退）
 * attestationStatus 映射：VALID 绑定成功=10；OBSERVE 下 INVALID/带了 proof 未绑定=20；没带 proof 或服务端未校验=30。
 */
@DgsComponent
class InstallFetcher(
    private val installFacade: InstallFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
    private val rateLimiter: RateLimiter,
    private val rlProps: RateLimitProperties,
    private val attestGuard: AttestGuard,
    private val serverConfigFacade: com.ifmix.core.api.modules.project.ProjectServerConfigFacade,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @DgsMutation(field = "m_install_createInstall")
    fun createInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>?): CreateInstallResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val clientIp = ctx.clientIp ?: "unknown"
        val pid = ctx.mustGetProjectId()

        // 1. 入口短窗口 100/60s/IP（验签前，保护验签消耗的 CPU）
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$pid:install:ip:min:$clientIp",
            rlProps.install.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many createInstall", retryAfterSec = rl.retryAfterSec)
        }

        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input?.get("deviceInfo") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val storeType = (input?.get("storeType") as? Number)?.toInt()

        // 2. proof / proofStatus 组合校验（§5.1 表：两者同时非空 → 400000；proofStatus ∉ {null,20} → 400000；输入上限 §5.7）
        val proofStatus = (input?.get("proofStatus") as? Number)?.toInt()
        val bundle = attestGuard.parseProofInput(input, proofStatus)

        // 3. 纯技术验证（不套 mode）→ 套 §4.3 矩阵（ENFORCE+INVALID → 403001；ENFORCE+无 proof/UNAVAILABLE → 403001/503002）
        val verification = attestGuard.verifyProof(ctx, bundle, proofStatus)
        val mode = configMode(ctx)
        val decision = attestGuard.decideCreateInstall(verification, mode)

        // 4. 日窗口（验签后，按验证结果分桶；两个计数器独立，互不影响）
        val attested = decision == AttestGuard.CreateInstallDecision.VERIFIED
        val (dayKey, dayLimit) = if (attested) {
            "ratelimit:$pid:install:ip:day:attested:$clientIp" to rlProps.install.attestedIpDay
        } else {
            "ratelimit:$pid:install:ip:day:unverified:$clientIp" to rlProps.install.unverifiedIpDay
        }
        when (val rl = rateLimiter.check(Window.UTC_DAY, dayKey, dayLimit)) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.INSTALL_DAILY_LIMITED, "too many createInstall", retryAfterSec = rl.retryAfterSec)
        }

        // 5. 一次性消费 challenge（日窗口通过后才消费，§4.6；replay → 403001；DEGRADED 放行由 guard 打 attest.redis_degraded）
        attestGuard.consume(verification)

        // 6. 事务内绑定（§5.3；key_reused / 并发唯一冲突在事务阶段，已扣额度不退——§4.6）
        val verifiedProof = (verification as? AttestGuard.Verification.Valid)?.proof
        val res = try {
            globalTx.withTx(ctx) { txCtx ->
                installFacade.createInstallWithProof(txCtx, deviceInfo, storeType, verifiedProof)
            }
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // §5.3：并发同 keyId（两个不同 challenge）唯一约束兜底 → 失败方回滚 → 403001(key_reused)，客户端转 recover
            throw ApiError(ErrorCode.ATTESTATION_FAILED, "attestation key already bound (key_reused)")
        }

        // §5.9：VALID 且 storeType 交叉不一致 → 只打日志不拒绝（OBSERVE/ENFORCE 同）
        if (verifiedProof != null && verifiedProof.provider == AttestGuard.PROVIDER_IOS && storeType != null && storeType != 10) {
            attestGuard.logStoreMismatch(pid, verifiedProof.provider, storeType)
        }

        val attestationStatus = when (decision) {
            AttestGuard.CreateInstallDecision.VERIFIED -> 10 // VALID 且绑定成功
            AttestGuard.CreateInstallDecision.NOT_PERSISTED -> 20 // 带了 proof 但没绑定（OBSERVE 下 INVALID 等）
            AttestGuard.CreateInstallDecision.NOT_ATTEMPTED -> 30 // 没带 proof / 服务端未校验
        }
        return CreateInstallResult(installId = res.installId, installToken = res.installToken, attestationStatus = attestationStatus)
    }

    /** 读 project 级 mode（§4.3 单一 mode，作用于所有已配置 provider；null = 未配置）。 */
    private fun configMode(ctx: ActionContext) =
        serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode

    @DgsMutation(field = "m_install_updateInstall")
    fun updateInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>): UpdateInstallResult {
        // 需 token（install 或 customer），取 iid
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val installId = ctx.tokenInstallId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "install token required")
        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input["deviceInfo"] as? Map<String, Any?>
        val ok = globalTx.withTx(ctx) { txCtx ->
            installFacade.updateInstall(
                txCtx, installId,
                input["firebaseInstallId"] as? String,
                input["fcmToken"] as? String,
                deviceInfo,
                (input["scanResultNotiEnabled"] as? Boolean),
                (input["deepResearchNotiEnabled"] as? Boolean),
            )
        }
        return UpdateInstallResult(success = ok)
    }

    // ===== m_install_createAttestChallenge（§5.1：无鉴权；100/60s/IP；纯计算不碰 Redis）=====

    @DgsMutation(field = "m_install_createAttestChallenge")
    fun createAttestChallenge(dfe: DgsDataFetchingEnvironment): AttestChallengeResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val clientIp = ctx.clientIp ?: "unknown"
        val pid = ctx.mustGetProjectId()
        // 短窗口 100/60s/IP（挑战是纯 HMAC 计算，与 createInstall 入口对齐，避免 CGNAT 瓶颈）
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$pid:attest-challenge:ip:min:$clientIp",
            rlProps.attestChallenge.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many createAttestChallenge", retryAfterSec = rl.retryAfterSec)
        }

        // enabled = 全局开关 && 配置可解析 && mode!=OFF && x-client-platform header 对应平台子对象存在（§5.1 v5 注）
        val enabled = attestGuard.isChallengeEnabled(pid, ctx.clientPlatform)
        if (!enabled) {
            // 客户端据此不生成 key，直接走 no-proof createInstall
            return AttestChallengeResult(enabled = false, challenge = null, expiresInSec = AttestChallengeCodec.CHALLENGE_CLIENT_TTL_SEC)
        }
        val challenge = attestGuard.issueChallenge(pid)
        return AttestChallengeResult(enabled = true, challenge = challenge, expiresInSec = AttestChallengeCodec.CHALLENGE_CLIENT_TTL_SEC)
    }

    // ===== m_install_recoverInstall（§3.3：无鉴权；10/60s/IP；与 mode/全局开关无关——决策 9）=====

    @DgsMutation(field = "m_install_recoverInstall")
    fun recoverInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>): CreateInstallResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val clientIp = ctx.clientIp ?: "unknown"
        val pid = ctx.mustGetProjectId()
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$pid:recover-install:ip:min:$clientIp",
            rlProps.recoverInstall.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many recoverInstall", retryAfterSec = rl.retryAfterSec)
        }

        val keyId = input["keyId"] as? String ?: throw ApiError(ErrorCode.INVALID_REQUEST, "keyId is required")
        val assertion = (input["assertion"] as? String) ?: throw ApiError(ErrorCode.INVALID_REQUEST, "assertion is required")
        val challenge = input["challenge"] as? String ?: throw ApiError(ErrorCode.INVALID_REQUEST, "challenge is required")

        // recover 只要求 ios 配置存在（未配置 → 400000，决策 9：与 mode/全局开关无关）
        val config = serverConfigFacade.findAttestConfig(pid)
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "ios attest config not set for this project")
        val ios = config.ios ?: throw ApiError(ErrorCode.INVALID_REQUEST, "ios attest config not set for this project")

        // a. challenge 签名/时效（secret 缺失 → 503002；challenge 无效 → 403001；GuardError 直接外抛，保留 retryAfterSec 语义）
        try {
            attestGuard.checkRecoverChallenge(pid, challenge)
        } catch (e: AttestGuard.GuardError) {
            throw e
        }

        // b. 只读查绑定（事务外预查；最终结果以事务内条件更新为准）
        val binding = installFacade.findAttestationBySubject(ctx, AttestGuard.PROVIDER_IOS, keyId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "attestation key not bound")
        if (binding.status != AttestationStatuses.ACTIVE) {
            throw ApiError(ErrorCode.ATTEST_KEY_BLOCKED, "attestation key is not active (status=${binding.status})")
        }
        val storedCounter = binding.signCount

        // c. WebAuthn4J 验 assertion（publicKey 公钥、rpIdHash、nonce=SHA256(authData‖SHA256(UTF8(clientData)))、counter>sign_count）
        val clientData = "ifmix-install-recover-v1\n" + pid + "\n" + challenge
        val assertionBytes = decodeBase64Flexible(assertion, "assertion")
        val assertionSuccess = when (
            val v = attestGuard.appAttestVerifier
                .verifyAssertion(
                    keyId = keyId,
                    assertion = assertionBytes,
                    clientData = clientData.toByteArray(Charsets.UTF_8),
                    teamId = ios.teamId,
                    bundleId = ios.bundleId,
                    storedPublicKey = binding.publicKey ?: byteArrayOf(),
                    storedCounter = storedCounter,
                )
        ) {
            is AppAttestVerification.AssertionSuccess -> v
            is AppAttestVerification.Invalid ->
                throw ApiError(ErrorCode.ATTESTATION_FAILED, "invalid recover assertion (${v.reason.code})")
            is AppAttestVerification.Unavailable ->
                throw ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attestation verification temporarily unavailable")
            is AppAttestVerification.AttestationSuccess ->
                throw ApiError(ErrorCode.ATTESTATION_FAILED, "unexpected attestation verification result")
        }
        // w4j 已保证 presented ≤ stored；counter > sign_count 的权威校验在事务内条件更新
        // （sign_count < :new；0 行 → 403001，§3.3-e），不在事务外 require（避免 presented==stored 时 500）。
        // d. 一次性消费（Redis 异常 → guard 打节流 ERROR attest.redis_degraded，跳过重放保护，可用性优先；replay → 403001）
        attestGuard.consumeChallenge(challenge)

        // e. 事务内条件更新 sign_count（0 行 → 403001 并发重放或期间被封禁/退役）+ 重签 installToken
        val res = globalTx.withTx(ctx) { txCtx ->
            installFacade.recoverInstall(txCtx, binding, assertionSuccess.newCounter)
        }
        recoverLog(ctx, "ok", null)
        return CreateInstallResult(installId = res.installId, installToken = res.installToken, attestationStatus = 10)
    }

    /** §5.5/§2 决策 11：event=install.recover 日志补 mode/provider 字段（不打印 keyId/证明原文）。 */
    private fun recoverLog(ctx: ActionContext, result: String, reason: String?) {
        val mode = serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode?.name ?: "OFF"
        log.info("event=install.recover mode={} provider={} result={} reason={}", mode, AttestGuard.PROVIDER_IOS, result, reason)
    }

    // ===== m_install_attestExisting（§6.7：严格只认 installToken；10/60s/IP + 3/install/UTC 日）=====

    @DgsMutation(field = "m_install_attestExisting")
    fun attestExisting(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>): AttestExistingResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)

        // 1. 鉴权：严格只认 installToken（type=5 && actorId==null && tokenInstallId!=null，否则 401000）
        val ok = when {
            ctx.tokenType != AuthJwtService.TOKEN_TYPE_INSTALL -> false
            ctx.actorId != null -> false
            else -> ctx.tokenInstallId != null
        }
        if (!ok) throw ApiError(ErrorCode.UNAUTHORIZED, "installToken required for attestExisting")
        val installId = ctx.tokenInstallId!!
        val pid = ctx.mustGetProjectId()

        // 2. IP 短窗口 10/60s
        val clientIp = ctx.clientIp ?: "unknown"
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$pid:attest-existing:ip:min:$clientIp",
            rlProps.attestExisting.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many attestExisting", retryAfterSec = rl.retryAfterSec)
        }

        // 3. 服务端未启用（OFF / 未配置 / 全局开关关）→ 30（NOT_EVALUATED，客户端废弃 key 等下次启动）
        if (!attestGuard.isAttestationEnabled(pid)) {
            return AttestExistingResult(attestationStatus = 30)
        }

        // 4. verifyProof（§5.1 组合校验 + §5.7 输入上限；INVALID → 20；UNAVAILABLE → 503002）
        val proofStatus: Int? = null
        @Suppress("UNCHECKED_CAST")
        val proofMap = input["proof"] as? Map<String, Any?>
        val bundle = attestGuard.parseProofInput(mapOf("proof" to proofMap), proofStatus)
        val verification = attestGuard.verifyProof(ctx, bundle, proofStatus)
        when (verification) {
            is AttestGuard.Verification.Valid -> Unit
            is AttestGuard.Verification.Invalid -> {
                // INVALID 与 mode 无关（ENFORCE 也一样）：返回 20，不报错、不写记录
                return AttestExistingResult(attestationStatus = 20)
            }
            is AttestGuard.Verification.Unavailable ->
                throw ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attestation verification temporarily unavailable (${verification.reason.code})")
            is AttestGuard.Verification.NotEvaluated ->
                return AttestExistingResult(attestationStatus = 30)
            is AttestGuard.Verification.Disabled,
            is AttestGuard.Verification.NoProof ->
                return AttestExistingResult(attestationStatus = 30)
        }
        val verified = (verification as AttestGuard.Verification.Valid).proof

        // 5. 预查 key 绑定（只读；最终结果以第 8 步事务内为准）
        val precheck = installFacade.findAttestationBySubject(ctx, verified.provider, verified.subject ?: "")
        when {
            precheck != null && precheck.installId == installId && precheck.status == AttestationStatuses.ACTIVE -> {
                // 同 install ACTIVE → 幂等 10，**跳过额度与消费**（不占新 key 额度、不消费 challenge）
                return AttestExistingResult(attestationStatus = 10)
            }
            precheck != null && precheck.status != AttestationStatuses.ACTIVE ->
                throw ApiError(ErrorCode.ATTEST_KEY_BLOCKED, "attestation key is not active (status=${precheck.status})")
            precheck != null ->
                throw ApiError(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL, "attestation key is bound to another install")
            else -> Unit
        }

        // 6. 新 key 才查：3 把/install/UTC 日 → 超限 429002（retryAfterSec=到 UTC 零点秒数）
        when (val rl = rateLimiter.check(
            Window.UTC_DAY,
            "ratelimit:$pid:attest-existing:install:day:$installId",
            rlProps.attestExisting.installDay,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.INSTALL_DAILY_LIMITED, "too many attestExisting", retryAfterSec = rl.retryAfterSec)
        }

        // 7. 一次性消费 challenge（replay → 403001；DEGRADED 放行，guard 打 attest.redis_degraded）
        attestGuard.consume(verification)

        // 8. 事务内权威判定（§6.7）：锁 install 行（404001）→ 锁内重查 → 幂等/403002/409001/满 5 把 retire + 插入
        val txResult: AttestExistingResult = try {
            globalTx.withTx(ctx) { txCtx ->
                installFacade.attestExisting(txCtx, installId, verified).let { _ ->
                    AttestExistingResult(attestationStatus = 10)
                }
            }
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // subject 唯一约束冲突（另一个 install 并发绑定同一把 key）→ 回滚后重查映射 10/409001
            val recheck = globalTx.withTx(ctx) { txCtx ->
                installFacade.attestExistingRecheckAfterConflict(txCtx, installId, verified.provider, verified.subject)
            }
            if (recheck) {
                AttestExistingResult(attestationStatus = 10)
            } else {
                throw ApiError(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL, "attestation key is bound to another install")
            }
        }
        attestLog(ctx, "ok", null)
        return txResult
    }

    /** §5.5：event=install.attest 日志（attestExisting 路径；与 Guard 内的纯技术日志分开，带 action 语义）。 */
    private fun attestLog(ctx: ActionContext, result: String, reason: String?) {
        val mode = serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode?.name ?: "OFF"
        log.info("event=install.attest_existing mode={} provider={} result={} reason={}", mode, AttestGuard.PROVIDER_IOS, result, reason)
    }

    /** base64（标准或 url 字母表）解码，失败 → 400000（§5.7 输入上限：assertion ≤ 4KB 由 schema 侧校验）。 */
    private fun decodeBase64Flexible(raw: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(raw)
    } catch (_: IllegalArgumentException) {
        try {
            Base64.getUrlDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "$field is not valid base64")
        }
    }
}
