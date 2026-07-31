package com.ifmix.api.core.service.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ClientPlatform
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.service.appconfig.AppConfig
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 微信开放平台 OAuth2 验证器。
 *
 * 流程：客户端传 authorization code → 后端用 code+appSecret 向微信换 access_token+unionid
 * → 再用 access_token 获取用户资料（昵称/头像）。
 */
class WechatVerifier(private val restClient: RestClient) : ProviderVerifier {
    override val provider = "wechat"

    override fun verify(config: AppConfig, platform: ClientPlatform?, credential: String): VerifiedProvider {
        val appId = config.wechatAppId
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appId not configured")
        val appSecret = config.wechatAppSecret
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appSecret not configured")

        // 1. Exchange code for access_token + openid + unionid
        val tokenRes = try {
            restClient.get()
                .uri("/sns/oauth2/access_token?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                    appId, appSecret, credential)
                .retrieve()
                .body(WechatTokenResponse::class.java)
        } catch (e: RestClientException) {
            throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat api error: ${e.message}")
        }

        if (tokenRes == null || (tokenRes.errcode != null && tokenRes.errcode != 0)) {
            throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: ${tokenRes?.errmsg ?: "empty response"}")
        }

        val unionId = tokenRes.unionid
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: unionid missing, ensure app is bound to open platform")

        // 2. Fetch user profile
        val userRes = try {
            restClient.get()
                .uri("/sns/userinfo?access_token={token}&openid={openid}",
                    tokenRes.accessToken, tokenRes.openid)
                .retrieve()
                .body(WechatUserResponse::class.java)
        } catch (e: RestClientException) {
            // User info fetch failure is non-fatal — proceed without profile
            null
        }

        return VerifiedProvider(
            accountId = unionId,
            email = null,
            emailVerified = false,
            phone = null,
            userMetadata = mapOf(
                "name" to userRes?.nickname,
                "picture" to userRes?.headimgurl,
                "openid" to tokenRes.openid,
            ),
        )
    }
}

/** 微信 /sns/oauth2/access_token 响应 */
data class WechatTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)

/** 微信 /sns/userinfo 响应 */
data class WechatUserResponse(
    val nickname: String? = null,
    val headimgurl: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    val sex: Int? = null,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)
