package com.ifmix.core.api.infra.http

/**
 * wire 加密 payload 非法（格式 / kid 未知 / GCM 认证失败 / 低阶点等）的统一异常。
 * 对外不区分原因（filter 固定返回 400003）；[kid] 仅进服务端日志，供轮换时观察旧 kid 流量。
 */
class WireCryptoException(val kid: Int? = null) : RuntimeException("wire decrypt failed")
