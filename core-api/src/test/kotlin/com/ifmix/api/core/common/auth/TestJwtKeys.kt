package com.ifmix.api.core.common.auth

import com.ifmix.core.api.infra.auth.AuthJwtKeys
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator

/**
 * 单测用 Ed25519 私钥（进程内随机生成，仅测试）。
 * AuthJwtKeys 现要求显式配置私钥（缺失 fail-fast），Spring 上下文走 application-test.yml 的静态 key。
 */
fun testAuthJwtKeys(kid: String = "test-key"): AuthJwtKeys =
    AuthJwtKeys(OctetKeyPairGenerator(Curve.Ed25519).keyID(kid).generate().toJSONString())
