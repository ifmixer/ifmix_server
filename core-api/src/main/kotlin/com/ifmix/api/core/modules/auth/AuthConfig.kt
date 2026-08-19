package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.AuthJwtKeys
import com.ifmix.api.core.common.auth.AuthJwtService
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.modules.app.repo.AppConfigRepo
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.scheduling.annotation.EnableAsync
import com.ifmix.api.core.modules.auth.handler.AuthEntityHandler
import com.ifmix.api.core.modules.auth.repo.AppRefreshTokenRepo
import com.ifmix.api.core.modules.auth.repo.AuthProviderIdentityRepo
import com.ifmix.api.core.modules.auth.repo.AppUserRepo
import com.ifmix.api.core.modules.auth.repo.AuthDeviceSecretRepo
import com.ifmix.api.core.modules.auth.repo.UserInstallBindingRepo

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
    fun providerVerifiers(googleJwtDecoder: JwtDecoder, appleJwtDecoder: JwtDecoder): Map<String, ProviderVerifier> =
        mapOf("google" to GoogleVerifier(googleJwtDecoder), "apple" to AppleVerifier(appleJwtDecoder))

    @Bean
    fun authJwtService(
        @Value("\${app.auth.jwt-private-key:}") privateJwk: String,
    ): AuthJwtService = AuthJwtService(AuthJwtKeys(privateJwk.ifBlank { null }), issuer, accessTtlSec)

    @Bean
    fun authService(
        appConfigRepo: AppConfigRepo,
        providerVerifiers: Map<String, ProviderVerifier>,
        authJwtService: AuthJwtService,
        providerIdentityRepo: AuthProviderIdentityRepo,
        appUserRepo: AppUserRepo,
        deviceSecretRepo: AuthDeviceSecretRepo,
        refreshRepo: AppRefreshTokenRepo,
        txRunner: TxRunner,
        events: ApplicationEventPublisher,
    ): AuthService = AuthService(
        appConfigRepo, providerVerifiers, authJwtService, providerIdentityRepo, appUserRepo,
        deviceSecretRepo, refreshRepo, txRunner, events, accessTtlSec,
    )

    @Bean
    fun authEntityHandler(
        providerIdentityRepo: AuthProviderIdentityRepo,
        deviceSecretRepo: AuthDeviceSecretRepo,
    ) = AuthEntityHandler(providerIdentityRepo, deviceSecretRepo)

    @Bean
    fun authFacade(
        appConfigRepo: AppConfigRepo,
        providerVerifiers: Map<String, ProviderVerifier>,
        authJwtService: AuthJwtService,
        appUserRepo: AppUserRepo,
        refreshRepo: AppRefreshTokenRepo,
        entityHandler: AuthEntityHandler,
        txRunner: TxRunner,
        events: ApplicationEventPublisher,
    ): AuthFacade = AuthFacade(
        appConfigRepo, providerVerifiers, authJwtService, appUserRepo, refreshRepo,
        entityHandler, txRunner, events, accessTtlSec,
    )

    @Bean
    fun mergeOnLoginListener(
        mongo: org.springframework.data.mongodb.core.MongoTemplate,
        txRunner: TxRunner,
        bindingRepo: UserInstallBindingRepo,
    ) = MergeOnLoginListener(mongo, txRunner, bindingRepo)
}
