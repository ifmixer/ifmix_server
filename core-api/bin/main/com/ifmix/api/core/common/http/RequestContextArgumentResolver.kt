package com.ifmix.api.core.common.http

import org.bson.types.ObjectId
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** 把校验后的请求头组装成 RequestContext，注入到控制器方法参数。 */
class RequestContextArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == RequestContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = RequestContext(
        appId = header(webRequest, RequestHeaders.APP_ID) ?: "",
        installId = header(webRequest, RequestHeaders.INSTALL_ID),
        lang = header(webRequest, RequestHeaders.LANG),
        currency = header(webRequest, RequestHeaders.CURRENCY),
        country = header(webRequest, RequestHeaders.COUNTRY),
        clientPlatform = ClientPlatform.fromHeader(header(webRequest, RequestHeaders.CLIENT_PLATFORM)),
    )

    private fun header(request: NativeWebRequest, name: String): String? =
        request.getHeader(name)?.takeIf { it.isNotBlank() }
}
