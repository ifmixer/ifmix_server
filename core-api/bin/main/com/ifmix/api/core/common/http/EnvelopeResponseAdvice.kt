package com.ifmix.api.core.common.http

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * 把控制器返回的裸 DTO 自动包成 Envelope。
 * 跳过 String 返回、框架自身控制器（springframework/springdoc）、已是 Envelope 的返回。
 */
@RestControllerAdvice
class EnvelopeResponseAdvice : ResponseBodyAdvice<Any> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean {
        if (StringHttpMessageConverter::class.java.isAssignableFrom(converterType)) return false
        val pkg = returnType.containingClass.packageName
        return !pkg.startsWith("org.springframework") && !pkg.startsWith("org.springdoc")
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? = if (body is Envelope<*>) body else Envelope.ok(body)
}
