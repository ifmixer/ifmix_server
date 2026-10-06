package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.dto.auth.install.AttestChallengeRes
import com.ifmix.core.api.dto.auth.install.AttestExistingInput
import com.ifmix.core.api.dto.auth.install.AttestExistingRes
import com.ifmix.core.api.dto.auth.install.CreateInstallInput
import com.ifmix.core.api.dto.auth.install.CreateInstallRes
import com.ifmix.core.api.dto.auth.install.RecoverInstallInput
import com.ifmix.core.api.dto.auth.install.UpdateInstallInput
import com.ifmix.core.api.dto.auth.install.UpdateInstallRes
import com.ifmix.core.api.entity.auth.install.AttestationStatuses
import com.ifmix.core.api.infra.attest.AttestChallengeCodec
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.attest.AppAttestVerification
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * install 模块 API controller（rollout M2）。
 *
 * 五个 action 的编排（IP 限流 / proof 校验 / attest 决策 / 日窗口 / challenge 消费 / 事务边界）
 * 自 InstallFetcher **逐行平移**，仅替换传输层：`fromDfe(dfe, requireActorType = null)` →
 * [ActionContextFactory.fromRpc]（meta.accessToken 自验；token 要求由既有内部校验精确执行）。
 * 语义红线：错误码 401000/403001/403002/409001/404001/429000/429002 与 retryAfterSec 逐一保留。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Install API", description = "install 模块（身份引导/attestation）")
class InstallApiController(
    private val installFacade: InstallFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxFactory: ActionContextFactory,
    private val rateLimiter: RateLimiter,
    private val rlProps: RateLimitProperties,
    private val attestGuard: AttestGuard,
    private val serverConfigFacade: ProjectServerConfigFacade,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(operationId = "m_auth_install_create")
    @PostMapping("m_auth_install_create", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createInstall(request: HttpServletRequest, @RequestBody body: ApiRequestBody<CreateInstallInput>): ResponseEntity<Envelope<CreateInstallRes>> {
        val ctx = ctxFactory.fromRpc(request, InstallSpecs.CREATE_INSTALL, body.meta)
        val clientIp = ctx.clientIp ?: "unknown"
        val pid = ctx.mustGetProjectId()
        val input = body.requireInput()

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

        // 2. proof / proofStatus 组合校验（§5.1 表：两者同时非空 → 400000；proofStatus ∉ {null,20} → 400000）
        val bundle = attestGuard.parseProofInput(mapOf("proof" to input.proof), input.proofStatus)

        // 3. 纯技术验证（不套 mode）→ 套 §4.3 矩阵（ENFORCE+INVALID → 403001；ENFORCE+无 proof/UNAVAILABLE → 403001/503002）
        val verification = attestGuard.verifyProof(ctx, bundle, input.proofStatus)
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

        // 5. 一次性消费 challenge（日窗口通过后才消费，§4.6；replay → 403001）
        attestGuard.consume(verification)

        // 6. 事务内绑定（§5.3；key_reused / 并发唯一冲突在事务阶段，已扣额度不退——§4.6）
        val verifiedProof = (verification as? AttestGuard.Verification.Valid)?.proof
        val res = try {
            globalTx.withTx(ctx) { txCtx ->
                installFacade.createInstallWithProof(txCtx, input.deviceInfo, input.storeType, verifiedProof)
            }
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // §5.3：并发同 keyId（两个不同 challenge）唯一约束兜底 → 失败方回滚 → 403001(key_reused)，客户端转 recover
            throw ApiError(ErrorCode.ATTESTATION_FAILED, "attestation key already bound (key_reused)")
        }

        // §5.9：VALID 且 storeType 交叉不一致 → 只打日志不拒绝（OBSERVE/ENFORCE 同）
        if (verifiedProof != null && verifiedProof.provider == AttestGuard.PROVIDER_IOS && input.storeType != null && input.storeType != 10) {
            attestGuard.logStoreMismatch(pid, verifiedProof.provider, input.storeType)
        }

        val attestationStatus = when (decision) {
            AttestGuard.CreateInstallDecision.VERIFIED -> 10 // VALID 且绑定成功
            AttestGuard.CreateInstallDecision.NOT_PERSISTED -> 20 // 带了 proof 但没绑定（OBSERVE 下 INVALID 等）
            AttestGuard.CreateInstallDecision.NOT_ATTEMPTED -> 30 // 没带 proof / 服务端未校验
        }
        return ResponseEntity.ok(Envelope.ok(CreateInstallRes(installId = res.installId, installToken = res.installToken, attestationStatus = attestationStatus)).copy(reqId = ctx.requestId))
    }

    /** 读 project 级 mode（§4.3 单一 mode，作用于所有已配置 provider；null = 未配置）。 */
    private fun configMode(ctx: ActionContext) =
        serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode

    @Operation(operationId = "m_auth_install_updateOne")
    @PostMapping("m_auth_install_updateOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateInstall(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateInstallInput>): ResponseEntity<Envelope<UpdateInstallRes>> {
        val ctx = ctxFactory.fromRpc(request, InstallSpecs.UPDATE_INSTALL, body.meta)
        val installId = ctx.tokenInstallId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "install token required")
        val input = body.requireInput()
        val ok = globalTx.withTx(ctx) { txCtx ->
            installFacade.updateInstall(
                txCtx, installId,
                input.firebaseInstallId,
                input.fcmToken,
                input.deviceInfo,
                input.scanResultNotiEnabled,
                input.deepResearchNotiEnabled,
            )
        }
        return ResponseEntity.ok(Envelope.ok(UpdateInstallRes(success = ok)).copy(reqId = ctx.requestId))
    }

    // ===== m_auth_install_createAttestChallenge（§5.1：无鉴权；100/60s/IP；纯计算不碰 Redis）=====

    @Operation(operationId = "m_auth_install_createAttestChallenge")
    @PostMapping("m_auth_install_createAttestChallenge", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createAttestChallenge(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<AttestChallengeRes>> {
        val ctx = ctxFactory.fromRpc(request, InstallSpecs.CREATE_ATTEST_CHALLENGE, body.meta)
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

        // enabled = 全局开关 && 配置可解析 && mode!=OFF && x-client-platform meta 对应平台子对象存在（§5.1 v5 注）
        val enabled = attestGuard.isChallengeEnabled(pid, ctx.clientPlatform)
        if (!enabled) {
            // 客户端据此不生成 key，直接走 no-proof createInstall
            return ResponseEntity.ok(Envelope.ok(AttestChallengeRes(enabled = false, challenge = null, expiresInSec = AttestChallengeCodec.CHALLENGE_CLIENT_TTL_SEC)).copy(reqId = ctx.requestId))
        }
        val challenge = attestGuard.issueChallenge(pid)
        return ResponseEntity.ok(Envelope.ok(AttestChallengeRes(enabled = true, challenge = challenge, expiresInSec = AttestChallengeCodec.CHALLENGE_CLIENT_TTL_SEC)).copy(reqId = ctx.requestId))
    }

    // ===== m_auth_install_recover（§3.3：无鉴权；10/60s/IP；与 mode/全局开关无关——决策 9）=====

    @Operation(operationId = "m_auth_install_recover")
    @PostMapping("m_auth_install_recover", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun recoverInstall(request: HttpServletRequest, @RequestBody body: ApiRequestBody<RecoverInstallInput>): ResponseEntity<Envelope<CreateInstallRes>> {
        val ctx = ctxFactory.fromRpc(request, InstallSpecs.RECOVER_INSTALL, body.meta)
        val clientIp = ctx.clientIp ?: "unknown"
        val pid = ctx.mustGetProjectId()
        val input = body.requireInput()
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$pid:recover-install:ip:min:$clientIp",
            rlProps.recoverInstall.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many recoverInstall", retryAfterSec = rl.retryAfterSec)
        }

        // recover 只要求 ios 配置存在（未配置 → 400000，决策 9：与 mode/全局开关无关）
        val config = serverConfigFacade.findAttestConfig(pid)
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "ios attest config not set for this project")
        val ios = config.ios ?: throw ApiError(ErrorCode.INVALID_REQUEST, "ios attest config not set for this project")

        // a. challenge 签名/时效（secret 缺失 → 503002；challenge 无效 → 403001；GuardError 直接外抛，保留 retryAfterSec 语义）
        try {
            attestGuard.checkRecoverChallenge(pid, input.challenge)
        } catch (e: AttestGuard.GuardError) {
            throw e
        }

        // b. 只读查绑定（事务外预查；最终结果以事务内条件更新为准）
        val binding = installFacade.findAttestationBySubject(ctx, AttestGuard.PROVIDER_IOS, input.keyId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "attestation key not bound")
        if (binding.status != AttestationStatuses.ACTIVE) {
            throw ApiError(ErrorCode.ATTEST_KEY_BLOCKED, "attestation key is not active (status=${binding.status})")
        }
        val storedCounter = binding.signCount

        // c. WebAuthn4J 验 assertion（publicKey 公钥、rpIdHash、nonce=SHA256(authData‖SHA256(UTF8(clientData)))、counter>sign_count）
        val clientData = "ifmix-install-recover-v1\n" + pid + "\n" + input.challenge
        val assertionBytes = decodeBase64Flexible(input.assertion, "assertion")
        val assertionSuccess = when (
            val v = attestGuard.appAttestVerifier
                .verifyAssertion(
                    keyId = input.keyId,
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
        attestGuard.consumeChallenge(input.challenge)

        // e. 事务内条件更新 sign_count（0 行 → 403001 并发重放或期间被封禁/退役）+ 重签 installToken
        val res = globalTx.withTx(ctx) { txCtx ->
            installFacade.recoverInstall(txCtx, binding, assertionSuccess.newCounter)
        }
        recoverLog(ctx, "ok", null)
        return ResponseEntity.ok(Envelope.ok(CreateInstallRes(installId = res.installId, installToken = res.installToken, attestationStatus = 10)).copy(reqId = ctx.requestId))
    }

    /** §5.5/§2 决策 11：event=install.recover 日志补 mode/provider 字段（不打印 keyId/证明原文）。 */
    private fun recoverLog(ctx: ActionContext, result: String, reason: String?) {
        val mode = serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode?.name ?: "OFF"
        log.info("event=install.recover mode={} provider={} result={} reason={}", mode, AttestGuard.PROVIDER_IOS, result, reason)
    }

    // ===== m_auth_install_attest（§6.7：严格只认 installToken；10/60s/IP + 3/install/UTC 日）=====

    @Operation(operationId = "m_auth_install_attest")
    @PostMapping("m_auth_install_attest", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun attestExisting(request: HttpServletRequest, @RequestBody body: ApiRequestBody<AttestExistingInput>): ResponseEntity<Envelope<AttestExistingRes>> {
        val ctx = ctxFactory.fromRpc(request, InstallSpecs.ATTEST_EXISTING, body.meta)
        val input = body.requireInput()

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
            return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = 30)).copy(reqId = ctx.requestId))
        }

        // 4. verifyProof（§5.1 组合校验 + §5.7 输入上限；INVALID → 20；UNAVAILABLE → 503002）
        val proofStatus: Int? = null
        val bundle = attestGuard.parseProofInput(mapOf("proof" to input.proof), proofStatus)
        val verification = attestGuard.verifyProof(ctx, bundle, proofStatus)
        when (verification) {
            is AttestGuard.Verification.Valid -> Unit
            is AttestGuard.Verification.Invalid -> {
                // INVALID 与 mode 无关（ENFORCE 也一样）：返回 20，不报错、不写记录
                return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = 20)).copy(reqId = ctx.requestId))
            }
            is AttestGuard.Verification.Unavailable ->
                throw ApiError(ErrorCode.ATTESTATION_UNAVAILABLE, "attestation verification temporarily unavailable (${verification.reason.code})")
            is AttestGuard.Verification.NotEvaluated ->
                return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = 30)).copy(reqId = ctx.requestId))
            is AttestGuard.Verification.Disabled,
            is AttestGuard.Verification.NoProof ->
                return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = 30)).copy(reqId = ctx.requestId))
        }
        val verified = (verification as AttestGuard.Verification.Valid).proof

        // 5. 预查 key 绑定（只读；最终结果以第 8 步事务内为准）
        val precheck = installFacade.findAttestationBySubject(ctx, verified.provider, verified.subject ?: "")
        when {
            precheck != null && precheck.installId == installId && precheck.status == AttestationStatuses.ACTIVE -> {
                // 同 install ACTIVE → 幂等 10，**跳过额度与消费**（不占新 key 额度、不消费 challenge）
                return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = 10)).copy(reqId = ctx.requestId))
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
        val txResult: Int = try {
            globalTx.withTx(ctx) { txCtx ->
                installFacade.attestExisting(txCtx, installId, verified).let { _ ->
                    10
                }
            }
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // subject 唯一约束冲突（另一个 install 并发绑定同一把 key）→ 回滚后重查映射 10/409001
            val recheck = globalTx.withTx(ctx) { txCtx ->
                installFacade.attestExistingRecheckAfterConflict(txCtx, installId, verified.provider, verified.subject)
            }
            if (recheck) {
                10
            } else {
                throw ApiError(ErrorCode.ATTEST_KEY_BOUND_TO_OTHER_INSTALL, "attestation key is bound to another install")
            }
        }
        attestLog(ctx, "ok", null)
        return ResponseEntity.ok(Envelope.ok(AttestExistingRes(attestationStatus = txResult)).copy(reqId = ctx.requestId))
    }

    /** §5.5：event=install.attest 日志（attestExisting 路径；与 Guard 内的纯技术日志分开，带 action 语义）。 */
    private fun attestLog(ctx: ActionContext, result: String, reason: String?) {
        val mode = serverConfigFacade.findAttestConfig(ctx.mustGetProjectId())?.mode?.name ?: "OFF"
        log.info("event=install.attest_existing mode={} provider={} result={} reason={}", mode, AttestGuard.PROVIDER_IOS, result, reason)
    }

    /** base64（标准或 url 字母表）解码，失败 → 400000（§5.7 输入上限：assertion ≤ 4KB）。 */
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
