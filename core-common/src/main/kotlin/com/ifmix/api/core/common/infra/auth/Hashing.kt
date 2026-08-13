package com.ifmix.api.core.common.infra.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * 哈希与随机令牌生成纯函数。
 */
object Hashing {

    private val B64 = Base64.getUrlEncoder().withoutPadding()
    private const val TOKEN_BYTES = 32 // 256-bit token

    /** SHA-256 → base64url 小写字符串。 */
    fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return B64.encodeToString(hash).lowercase()
    }

    /** 生成随机 256-bit token（base64url）。 */
    fun randomTokenBase64Url(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        Random.nextBytes(bytes)
        return B64.encodeToString(bytes)
    }
}
