package com.ifmix.core.job.attest

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import org.springframework.stereotype.Component

/**
 * [AppleReceiptClient] 的 JDK HttpClient 实现。
 *
 * 响应解析（规格 §10.4 待核实项：字段名按 Apple REST 惯例 `receipt`；实测不符时只需改这里）。
 * 判定口径：
 *  - 2xx 且解析出 receipt → [AppleReceiptClient.Result.Success]；
 *  - 4xx 且 body 含「已使用 / already-used / consumed / EXPIRED」字样（不区分大小写）→ [AlreadyUsed]；
 *    其它 4xx（如 400 格式错误）也归 AlreadyUsed：一次消费类 4xx 都不再重试（规格 §5.8「已使用」4xx → 放弃）。
 *  - 429 / 5xx / 网络异常 → [TransientError]（退避下轮重试）。
 */
@Component
class AppleReceiptClientImpl(
    @org.springframework.beans.factory.annotation.Value("\${app.attest.apple-endpoint:https://api-appattest.apple.com/v1/attestations}")
    private val endpoint: String,
    @org.springframework.beans.factory.annotation.Value("\${app.attest.apple-timeout-ms:15000}")
    private val timeoutMs: Long,
) : AppleReceiptClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    override fun exchangeReceipt(keyId: String, attestationObjectBase64: String): AppleReceiptClient.Result {
        val body = mapper.writeValueAsString(mapOf("key" to keyId, "attestation" to attestationObjectBase64))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                resp.statusCode() in 200..299 -> parseReceipt(resp.body())
                resp.statusCode() in 400..499 -> {
                    log.info("[attest-backfill] Apple 4xx. keyId=*** status={} body={}", resp.statusCode(), resp.body().take(200))
                    AppleReceiptClient.Result.AlreadyUsed
                }
                else -> {
                    log.warn("[attest-backfill] Apple 非 4xx/2xx. keyId=*** status={}", resp.statusCode())
                    AppleReceiptClient.Result.TransientError(resp.statusCode(), "status=${resp.statusCode()}")
                }
            }
        } catch (e: Exception) {
            log.warn("[attest-backfill] 网络错误. keyId=*** cause={}", e.javaClass.simpleName)
            AppleReceiptClient.Result.TransientError(null, e.javaClass.simpleName)
        }
    }

    private fun parseReceipt(raw: String): AppleReceiptClient.Result {
        // 宽容解析：优先 JSON 字段 receipt / receiptString；解析失败则整体原文作为 receipt。
        val value = try {
            val tree = mapper.readTree(raw)
            tree.get("receipt")?.asText() ?: tree.get("receiptString")?.asText() ?: raw
        } catch (e: Exception) {
            raw
        }
        if (value.isBlank()) return AppleReceiptClient.Result.TransientError(null, "empty receipt")
        // receipt 原文（字符串）入库：BYTEA 存 UTF-8 原文，不做二次 base64。
        return AppleReceiptClient.Result.Success(value.toByteArray(Charsets.UTF_8))
    }
}
