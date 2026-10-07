package com.ifmix.core.api.infra.attest

import com.webauthn4j.appattest.data.attestation.statement.AppleAppAttestAttestationStatement
import com.webauthn4j.converter.AttestationObjectConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 回归（2026-10-07 真机实测发现）：真机 proof 的 fmt 是 `apple-appattest`，核心库不认识——
 * 必须把 DeviceCheckCBORModule 注册进 ObjectConverter 的 CBOR mapper，否则所有真机 attestation
 * 都 InvalidTypeIdException → parse_failed。
 * 无真机 fixture：走与 DeviceCheckAttestationManager 相同的 AttestationObjectConverter 转换路径，
 * 用手工 CBOR（fmt=apple-appattest）锁定子类型注册。
 */
class AppAttestVerifierConverterTest {

    private val verifier = AppAttestVerifier(
        trustAnchors = AppAttestTrustAnchors(),
        verifyPermits = 1,
    )

    @Test
    fun `attestation object with fmt apple-appattest converts to AppleAppAttestAttestationStatement`() {
        val cbor = verifier.objectConverter.cborConverter.writeValueAsBytes(
            mapOf(
                "fmt" to "apple-appattest",
                // attStmt：AppleAppAttest = x5c 证书链 + receipt；转换期不做校验，最小合法结构即可
                "attStmt" to mapOf("x5c" to emptyList<Any>(), "receipt" to byteArrayOf(0)),
                // authData：rpIdHash(32) + flags(1) + signCount(4)，flags=0（无 attestedCredentialData）
                "authData" to ByteArray(37),
            ),
        )
        val obj = AttestationObjectConverter(verifier.objectConverter).convert(cbor)
        assertThat(obj!!.attestationStatement).isInstanceOf(AppleAppAttestAttestationStatement::class.java)
    }
}
