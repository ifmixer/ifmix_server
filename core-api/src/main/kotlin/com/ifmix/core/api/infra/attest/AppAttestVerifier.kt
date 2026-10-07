package com.ifmix.core.api.infra.attest

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import com.webauthn4j.appattest.DeviceCheckAssertionManager
import com.webauthn4j.appattest.converter.jackson.DeviceCheckCBORModule
import com.webauthn4j.appattest.DeviceCheckAttestationManager
import com.webauthn4j.appattest.authenticator.DCAppleDevice
import com.webauthn4j.appattest.authenticator.DCAppleDeviceImpl
import com.webauthn4j.appattest.data.DCAssertionParameters
import com.webauthn4j.appattest.data.DCAssertionRequest
import com.webauthn4j.appattest.data.DCAttestationParameters
import com.webauthn4j.appattest.data.DCAttestationRequest
import com.webauthn4j.appattest.server.DCServerProperty
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.Curve
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.util.ECUtil
import com.webauthn4j.verifier.CustomCoreAuthenticationVerifier
import com.webauthn4j.verifier.CustomCoreRegistrationVerifier
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.CertPathTrustworthinessVerifier
import com.webauthn4j.verifier.exception.BadAaguidException
import com.webauthn4j.verifier.exception.BadAttestationStatementException
import com.webauthn4j.verifier.exception.BadRpIdException
import com.webauthn4j.verifier.exception.BadSignatureException
import com.webauthn4j.verifier.exception.CertificateException as W4jCertificateException
import com.webauthn4j.verifier.exception.InconsistentClientDataTypeException
import com.webauthn4j.verifier.exception.MaliciousCounterValueException
import com.webauthn4j.verifier.exception.VerificationException
import java.io.IOException
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.concurrent.Semaphore

/**
 * iOS App Attest 验证（纯密码学，不套 mode、不写业务表；规格 §5.2）。
 *
 * - attestation：`DeviceCheckAttestationManager` + `DCAttestationRequest(keyId, attestationObject, clientDataHash)`
 *   + `DCServerProperty(teamId, bundleId, challenge)`。production / development 两个实例
 *   （`DCAttestationDataVerifier.production` = AAGUID 期望值检查：`appattest\0…0` vs `appattestdevelop`，
 *   dev/prod 共用同一 Apple 根证书），按 `ios.env` 选用。
 * - assertion：`DeviceCheckAssertionManager` + `DCAssertionRequest(keyId, assertion, clientDataHash)`，
 *   用存储公钥构造 `DCAppleDevice` 验签，返回 newCounter；counter > 存储值的权威校验由调用方
 *   在事务内做条件更新（w4j 内部也有一道 presented ≤ stored → MaliciousCounterValue 的防线）。
 * - 进程内信号量限制并发（规格 §3.1：Redis 故障期防验签把进程打满），拿不到许可 → UNAVAILABLE。
 *
 * 字节契约（§3.1，在本组件内集中实现，供文档向量测试锁定）：
 * ```
 * clientDataHash = SHA256(UTF8(challengeStr))                // attestation：对 76 字符 ASCII 串做 hash
 * expectedNonce  = SHA256(authData ‖ clientDataHash)         // 由 w4j AppleAppAttestAttestationStatementVerifier 校验
 * ```
 *
 * ObjectConverter 使用 webauthn4j 传递依赖的 Jackson 2（`ObjectMapper(CBORFactory)`）；
 * 与 Spring 的 tools.jackson 3 包名不同，同进程共存（已核实，规格 §10 第 3 项）。
 *
 * TODO: §10.1 真机 fixture — attestation / assertion 的端到端验证需要真机录制的 fixture（需真机，本期不做）。
 */
@Component
class AppAttestVerifier(
    private val trustAnchors: AppAttestTrustAnchors,
    @Value("\${app.attest.verify-permits:32}") verifyPermits: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val semaphore = Semaphore(verifyPermits.coerceAtLeast(1))

    /** 【2026-10-07 真机实测修复】DeviceCheckCBORModule 注册 apple-appattest 子类型——不注册则 CBOR 解析
     *  直接 InvalidTypeIdException（known type ids 只有 core 的 7 种），所有真机 proof 都 parse_failed。 */
    internal val objectConverter = ObjectConverter().apply {
        cborConverter.registerModule(DeviceCheckCBORModule())
    }

    // 信任锚资源缺失时不在启动期失败（AppAttestTrustAnchors 的降级策略），lazy 到第一次验签才报错。
    private val productionAttestationManager by lazy { createAttestationManager(production = true) }
    private val developmentAttestationManager by lazy { createAttestationManager(production = false) }
    private val assertionManager by lazy {
        DeviceCheckAssertionManager(emptyList<CustomCoreAuthenticationVerifier>(), objectConverter)
    }

    /**
     * 验证 attestation（createInstall 路径）。
     *
     * @param keyId      客户端原样 keyId（base64 / base64url 均可，内部解码；应等于 credentialId）
     * @param challenge  challengeStr 原文（76 字符 ASCII）；clientDataHash 在本方法内按 §3.1 计算
     * @param production ios.env：true=production / false=development（决定 AAGUID 期望值）
     */
    fun verifyAttestation(
        keyId: String,
        attestationObject: ByteArray,
        challenge: String,
        teamId: String,
        bundleId: String,
        production: Boolean,
    ): AppAttestVerification = withPermit {
        // §3.1 字节契约：clientDataHash = SHA256(UTF8(challengeStr))，对 challenge 字符串原文做 hash，不先 base64 解码
        val clientDataHash = sha256(challenge.toByteArray(Charsets.UTF_8))
        val manager = if (production) productionAttestationManager else developmentAttestationManager
        val request = DCAttestationRequest(decodeKeyId(keyId), attestationObject, clientDataHash)
        val serverProperty = DCServerProperty(teamId, bundleId, DefaultChallenge(challenge.toByteArray(Charsets.UTF_8)))
        val data = manager.validate(request, DCAttestationParameters(serverProperty))
        // validate 已通过（BeanAssertUtil 非空校验），attestationObject / authData / attestedCredentialData 必非空
        val authData = data.attestationObject!!.authenticatorData!!
        val credData = authData.attestedCredentialData!!
        val coseKey = credData.coseKey
        if (coseKey !is EC2COSEKey || coseKey.curve != Curve.SECP256R1) {
            return@withPermit AppAttestVerification.Invalid(AppAttestVerification.Reason.UNSUPPORTED_KEY)
        }
        val publicKeyBytes = ECUtil.createUncompressedPublicKey(coseKey.publicKey as ECPublicKey)
        val rawAuthData = extractAuthData(attestationObject)
            ?: return@withPermit AppAttestVerification.Invalid(AppAttestVerification.Reason.PARSE_FAILED)
        AppAttestVerification.AttestationSuccess(
            credentialId = credData.credentialId,
            publicKey = publicKeyBytes, // EC P-256 uncompressed point（0x04 ‖ X ‖ Y，65 字节）
            signCount = authData.signCount, // attestation 恒为 0（w4j 已校验）
            authData = rawAuthData,
        )
    }

    /**
     * 验证 assertion（recoverInstall 路径）。
     *
     * @param clientData     服务端拼装的 clientData 原文（§3.3：`"ifmix-install-recover-v1\n" + projectId + "\n" + challengeStr`）
     * @param storedPublicKey attestation 时存库的公钥（EC P-256 uncompressed point，65 字节）
     * @param storedCounter   库里的 sign_count；w4j 用它做 presented ≤ stored 防线
     * @return newCounter = assertion authData 的 signCount（调用方据此做事务内条件更新）
     */
    fun verifyAssertion(
        keyId: String,
        assertion: ByteArray,
        clientData: ByteArray,
        teamId: String,
        bundleId: String,
        storedPublicKey: ByteArray,
        storedCounter: Long,
    ): AppAttestVerification = withPermit {
        val clientDataHash = sha256(clientData)
        val keyIdBytes = decodeKeyId(keyId)
        val coseKey = EC2COSEKey.createFromUncompressedECCKey(storedPublicKey)
        val device: DCAppleDevice = DCAppleDeviceImpl(
            AttestedCredentialData(AAGUID.ZERO, keyIdBytes, coseKey),
            null,
            storedCounter,
            null,
        )
        val data = assertionManager.verify(
            DCAssertionRequest(keyIdBytes, assertion, clientDataHash),
            DCAssertionParameters(DCServerProperty(teamId, bundleId, null), device),
        )
        AppAttestVerification.AssertionSuccess(newCounter = data.authenticatorData!!.signCount)
    }

    // ---- internals ----

    private fun createAttestationManager(production: Boolean): DeviceCheckAttestationManager {
        val certPathVerifier: CertPathTrustworthinessVerifier = trustAnchors.certPathTrustworthinessVerifier()
        val manager = DeviceCheckAttestationManager(
            certPathVerifier,
            emptyList<CustomCoreRegistrationVerifier>(),
            objectConverter,
        )
        // production 标志 = AAGUID 期望值检查（appattest\0…0 vs appattestdevelop），默认 true
        manager.dcAttestationDataValidator.isProduction = production
        return manager
    }

    private inline fun withPermit(block: () -> AppAttestVerification): AppAttestVerification {
        if (!semaphore.tryAcquire()) {
            log.warn("event=attest.verify_permits_exhausted permits={}", semaphore.availablePermits())
            return AppAttestVerification.Unavailable
        }
        try {
            return block()
        } catch (e: Exception) {
            return mapException(e)
        } finally {
            semaphore.release()
        }
    }

    /** WebAuthn4J 异常 → 结构化 reason（供日志 `reason` 字段用，规格 §5.5）。 */
    private fun mapException(e: Exception): AppAttestVerification = when (e) {
        is IllegalStateException -> AppAttestVerification.Unavailable // 信任锚未就绪（资源缺失）
        is IllegalArgumentException -> {
            // parse_failed 不带原始异常无法定位（base64/CBOR/公钥解析都可能走到这）——记录类名+message
            log.warn("event=attest.parse_failed cause={} message={} rootCause={}", e.javaClass.simpleName, e.message, rootCauseOf(e))
            AppAttestVerification.Invalid(AppAttestVerification.Reason.PARSE_FAILED)
        }
        is DataConversionException -> {
            log.warn("event=attest.parse_failed cause={} message={} rootCause={}", e.javaClass.simpleName, e.message, rootCauseOf(e))
            AppAttestVerification.Invalid(AppAttestVerification.Reason.PARSE_FAILED)
        }
        is BadAttestationStatementException -> when {
            e.message?.contains("nonce", ignoreCase = true) == true ->
                AppAttestVerification.Invalid(AppAttestVerification.Reason.NONCE_MISMATCH)
            e.message?.contains("key identifier", ignoreCase = true) == true ->
                AppAttestVerification.Invalid(AppAttestVerification.Reason.KEY_MISMATCH)
            e.message?.contains("certificate", ignoreCase = true) == true ->
                AppAttestVerification.Invalid(AppAttestVerification.Reason.CHAIN_INVALID)
            else -> AppAttestVerification.Invalid(AppAttestVerification.Reason.ATTESTATION_INVALID)
        }
        is BadAaguidException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.AAGUID)
        is MaliciousCounterValueException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.COUNTER)
        is BadRpIdException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.RP_MISMATCH)
        is BadSignatureException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.SIGNATURE_INVALID)
        is InconsistentClientDataTypeException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.CLIENT_DATA_INVALID)
        is W4jCertificateException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.CHAIN_INVALID)
        is VerificationException -> AppAttestVerification.Invalid(AppAttestVerification.Reason.VERIFICATION_FAILED)
        else -> {
            // 未预期的异常：不当作 INVALID（避免把实现 bug 误判成伪造 proof），按 UNAVAILABLE 处理
            log.error("event=attest.verify_unexpected_error", e)
            AppAttestVerification.Unavailable
        }
    }

    private fun rootCauseOf(e: Throwable): String {
        var cur: Throwable = e
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return "${cur.javaClass.simpleName}: ${cur.message}"
    }

    /** keyId：客户端 base64（标准或 url alphabet）编码的 credentialId。 */
    private fun decodeKeyId(keyId: String): ByteArray = try {
        Base64.getDecoder().decode(keyId)
    } catch (_: IllegalArgumentException) {
        Base64.getUrlDecoder().decode(keyId)
    }

    /**
     * 从 attestationObject CBOR 里取 `authData` 原文（byte string）。
     * 与 WebAuthn4J `CoreRegistrationObject.extractAuthenticatorData` 相同的做法，保证与 nonce 校验用到的字节一致。
     */
    private fun extractAuthData(attestationObjectBytes: ByteArray): ByteArray? = try {
        objectConverter.cborConverter.readTree(attestationObjectBytes).get("authData")?.binaryValue()
    } catch (_: IOException) {
        null
    }

    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** 验证结果。reason 对齐规格 §5.5 日志 reason 枚举。 */
sealed interface AppAttestVerification {

    /** attestation 通过。 */
    @Suppress("ArrayInDataClass")
    data class AttestationSuccess(
        /** == keyId 解码后的 credentialId */
        val credentialId: ByteArray,
        /** EC P-256 uncompressed point（0x04 ‖ X ‖ Y，65 字节），入库 public_key BYTEA */
        val publicKey: ByteArray,
        /** attestation 恒为 0 */
        val signCount: Long,
        /** authenticatorData 原文（CBOR byte string 原样） */
        val authData: ByteArray,
    ) : AppAttestVerification

    /** assertion 通过；counter 的权威校验由调用方在事务内条件更新。 */
    data class AssertionSuccess(
        val newCounter: Long,
    ) : AppAttestVerification

    /** 验证失败（伪造 / 篡改 / 不匹配）。 */
    data class Invalid(val reason: Reason) : AppAttestVerification

    /** 暂时不可用（信号量耗尽 / 信任锚未就绪 / 未预期异常）。ENFORCE → 503002，OBSERVE → 放行。 */
    data object Unavailable : AppAttestVerification

    enum class Reason(val code: String) {
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
    }
}
