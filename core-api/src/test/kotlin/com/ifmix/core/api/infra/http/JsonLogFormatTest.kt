package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.OutputStreamAppender
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.logging.logback.StructuredLogEncoder
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream

/** 文件日志 JSON（logback-spring.xml 同款 StructuredLogEncoder logstash）：MDC 与 SLF4J key-value 都是顶层字段，一行一条。 */
class JsonLogFormatTest {

    @Test
    fun `mdc and key-values become top-level json fields on a single line`() {
        // 用 SLF4J 实际绑定的 context（MDC adapter 已就绪）；Spring Boot 启动时会把 Environment 放进去，测试手动放
        val lc = org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext
        lc.putObject(org.springframework.core.env.Environment::class.java.name, org.springframework.mock.env.MockEnvironment())
        val out = ByteArrayOutputStream()
        val encoder = StructuredLogEncoder().apply { context = lc; setFormat("logstash"); start() }
        val appender = OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            context = lc; this.encoder = encoder; outputStream = out; start()
        }
        val logger = lc.getLogger("json-log-format-test").apply { isAdditive = false; level = ch.qos.logback.classic.Level.DEBUG; addAppender(appender) }

        MDC.put("rid", "r-1"); MDC.put("cid", "c-1")
        try {
            logger.atWarn().addKeyValue("httpStatus", 503).addKeyValue("duration", 645L)
                .addKeyValue("req", "{\"a\":1}\nsecond line")
                .addKeyValue("headers", HeaderDump.of(org.springframework.mock.web.MockHttpServletRequest().apply {
                    addHeader("X-Project-Id", "antique"); addHeader("Authorization", "Bearer secret.jwt"); addHeader("Cookie", "s=1")
                })).log("request")
        } finally { MDC.remove("rid"); MDC.remove("cid") }

        val line = out.toString(Charsets.UTF_8).trimEnd()
        assertThat(line.contains('\n')).isFalse()
        val json = JsonMapper.builder().build().readTree(line)
        assertThat(json.get("message").asString()).isEqualTo("request")
        assertThat(json.get("rid").asString()).isEqualTo("r-1")
        assertThat(json.get("cid").asString()).isEqualTo("c-1")
        assertThat(json.get("httpStatus").asInt()).isEqualTo(503)
        assertThat(json.get("duration").asLong()).isEqualTo(645L)
        assertThat(json.get("req").asString()).isEqualTo("{\"a\":1}\nsecond line")
        // headers 是 JSON 对象（非字符串），name 小写，凭证脱敏
        val headers = json.get("headers")
        assertThat(headers.isObject).isEqualTo(true)
        assertThat(headers.get("x-project-id").asString()).isEqualTo("antique")
        assertThat(headers.get("authorization").asString()).isEqualTo("Bearer ***")
        assertThat(headers.get("cookie").asString()).isEqualTo("***")
    }
}
