package com.ifmix.api.core.infra.http

import com.ifmix.api.core.infra.auth.AuthInterceptor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

/** 把校验后的请求头组装成 RequestContext，注入到控制器方法参数。 */
class RequestContextArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == RequestContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        // 从 AuthInterceptor 读取已验证的 userId（如果拦截器已执行）
        val userId = (webRequest.getAttribute(AuthInterceptor.ATTR_USER_ID, RequestAttributes.SCOPE_REQUEST) as? String)

        return RequestContext(
            appId = header(webRequest, RequestHeaders.APP_ID) ?: "",
            installId = header(webRequest, RequestHeaders.INSTALL_ID)?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            },
            lang = header(webRequest, RequestHeaders.LANG),
            currency = header(webRequest, RequestHeaders.CURRENCY),
            country = header(webRequest, RequestHeaders.COUNTRY),
            clientPlatform = ClientPlatform.fromHeader(header(webRequest, RequestHeaders.CLIENT_PLATFORM)),
            userId = userId,
        )
    }

    private fun header(request: NativeWebRequest, name: String): String? =
        request.getHeader(name)?.takeIf { it.isNotBlank() }
}
