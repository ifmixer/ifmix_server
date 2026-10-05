package com.ifmix.core.api.infra.http

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * RPC 请求元信息（wire v3 信封的 meta 段，解密后随 input 一起传入）。
 *
 * 全部字段可空：缺失按「未提供」处理（容错，如明文 curl 调试场景不带 meta）。
 * 未知字段忽略（`ignoreUnknown = true`），服务端加字段时向前兼容旧客户端。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RequestMeta(
    /** 请求 id；缺失时由服务端 x-req-id header 通道（LogContext）补齐。 */
    val reqId: String? = null,
    /** 项目 slug（= GraphQL 的 x-project-id）。 */
    val projectId: String? = null,
    /** 纯 access token，无 Bearer 前缀（带前缀属协议错误，直接拒绝）。 */
    val accessToken: String? = null,
    /** BCP 47 locale（x-locale 等价）。 */
    val locale: String? = null,
    /** ISO 4217 三位币种码（x-currency 等价）。 */
    val currency: String? = null,
    /** ISO 3166-1 alpha-2 两位国家码（x-country 等价）。 */
    val country: String? = null,
    /** IANA 时区名（meta 独有，可选，无格式校验）。 */
    val userTz: String? = null,
    /** 客户端 App 版本号（x-app-version 等价，原样透传）。 */
    val appVersion: String? = null,
    /** 客户端热更新版本号（x-ota-version 等价，原样透传）。 */
    val otaVersion: String? = null,
    /** 客户端平台：android | ios | web（x-client-platform 等价）。 */
    val clientPlatform: String? = null,
    /** 设备型号（meta 独有，可选，仅记录用途）。 */
    val deviceModel: String? = null,
    /** 操作系统版本（meta 独有，可选，仅记录用途）。 */
    val osVersion: String? = null,
)
