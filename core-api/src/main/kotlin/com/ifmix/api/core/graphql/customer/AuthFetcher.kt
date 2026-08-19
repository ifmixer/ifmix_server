package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.AuthResult
import com.ifmix.api.core.graphql.generated.types.AuthUser
import com.ifmix.api.core.modules.auth.AuthExchangeResult
import com.ifmix.api.core.modules.auth.AuthFacade
import com.ifmix.api.core.modules.auth.AuthLoginResult
import com.ifmix.api.core.modules.auth.AuthRefreshResult
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import java.time.Instant

@DgsComponent
class AuthFetcher(private val authFacade: AuthFacade) {

    @DgsQuery(field = "query_auth_me")
    fun me(dfe: DgsDataFetchingEnvironment): AuthUser {
        val ctx = getContext(dfe)
        val user = authFacade.me(ctx)
        return AuthUser(id = user.id, email = user.email)
    }

    @DgsMutation(field = "mutation_auth_loginGoogle")
    fun loginGoogle(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AuthResult {
        val ctx = getContext(dfe)
        val idToken = input["idToken"] as String
        val deviceSecret = input["deviceSecret"] as? String
        val result = authFacade.loginWithProvider(ctx, "google", idToken, deviceSecret)
        return toAuthResult(result)
    }

    @DgsMutation(field = "mutation_auth_loginApple")
    fun loginApple(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AuthResult {
        val ctx = getContext(dfe)
        val idToken = input["idToken"] as String
        val deviceSecret = input["deviceSecret"] as? String
        val result = authFacade.loginWithProvider(ctx, "apple", idToken, deviceSecret)
        return toAuthResult(result)
    }

    @DgsMutation(field = "mutation_auth_exchange")
    fun exchange(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AuthResult {
        val ctx = getContext(dfe)
        val deviceSecret = input["deviceSecret"] as String
        val result = authFacade.exchange(ctx, deviceSecret)
        return toAuthResult(result)
    }

    @DgsMutation(field = "mutation_auth_refresh")
    fun refresh(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AuthResult {
        val ctx = getContext(dfe)
        val refreshToken = input["refreshToken"] as String
        val result = authFacade.refresh(ctx, refreshToken)
        return toAuthResult(result)
    }

    @DgsMutation(field = "mutation_auth_logout")
    fun logout(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): Boolean {
        val ctx = getContext(dfe)
        val refreshToken = input["refreshToken"] as String
        return authFacade.logout(ctx, refreshToken)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext<RequestContext>(dfe)

    private fun toAuthResult(result: AuthLoginResult): AuthResult = AuthResult(
        accessToken = result.accessToken,
        refreshToken = result.refreshToken,
        refreshExpiresAt = result.refreshExpiresAt,
        deviceSecret = result.deviceSecret,
        expiresIn = result.expiresIn,
        user = AuthUser(id = result.user.id, email = result.user.email),
    )

    private fun toAuthResult(result: AuthExchangeResult): AuthResult = AuthResult(
        accessToken = result.accessToken,
        refreshToken = result.refreshToken,
        refreshExpiresAt = result.refreshExpiresAt,
        deviceSecret = "",
        expiresIn = result.expiresIn,
        user = AuthUser(id = result.user.id, email = result.user.email),
    )

    private fun toAuthResult(result: AuthRefreshResult): AuthResult = AuthResult(
        accessToken = result.accessToken,
        refreshToken = result.refreshToken,
        refreshExpiresAt = result.refreshExpiresAt,
        deviceSecret = "",
        expiresIn = result.expiresIn,
        user = AuthUser(id = "", email = null),
    )
}
