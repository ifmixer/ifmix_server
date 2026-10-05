package com.ifmix.core.api.infra.http

/** wire 加密（x-proto-version: 2）payload 格式非法 / kid 未知 / GCM 认证失败 / 低阶点等，统一抛出。 */
class WireCryptoException(message: String) : Exception(message)
