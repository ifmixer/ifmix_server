package com.ifmix.api.core.testsupport

import org.springframework.context.MessageSource
import org.springframework.context.support.ResourceBundleMessageSource

/** 测试用 MessageSource：加载真实 classpath 的 messages_*.properties（UTF-8），验证真实 i18n 文案。 */
object TestMessages {
    val source: MessageSource = ResourceBundleMessageSource().apply {
        setBasename("i18n/messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
}
