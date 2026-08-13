package com.ifmix.api.core.common.modules.auth

import com.ifmix.api.core.common.infra.auth.AuthJwtKeys
import com.ifmix.api.core.common.infra.auth.AuthJwtService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 认证模块 bean 装配。
 */
@Configuration
@EnableAsync
class AuthConfig {

    @Value("\${app.auth.issuer:ifmix}")
    private val issuer: String = "ifmix"

    @Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long = 900L

    @Bean
    fun googleJwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"))
        }

    @Bean
    fun appleJwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("https://appleid.apple.com/auth/keys").build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer("https://appleid.apple.com"))
        }

    @Bean
    fun wechatRestClient(
        @Value("\${app.auth.wechat-api-url:https://api.weixin.qq.com}") baseUrl: String,
    ): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .build()
    }

    @Bean
    fun providerVerifiers(
        googleJwtDecoder: JwtDecoder,
        appleJwtDecoder: JwtDecoder,
        wechatRestClient: RestClient,
    ): Map<String, ProviderVerifier> = mapOf(
        "google" to GoogleVerifier(googleJwtDecoder),
        "apple" to AppleVerifier(appleJwtDecoder),
        "wechat" to WechatVerifier(wechatRestClient),
        "anonymous" to AnonymousVerifier(),
    )

    @Bean
    fun authJwtService(
        @Value("\${app.auth.jwt-private-key:}") privateJwk: String,
    ): AuthJwtService = AuthJwtService(AuthJwtKeys(privateJwk.ifBlank { null }), issuer, accessTtlSec)
}
