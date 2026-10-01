package com.ifmix.core.api.infra.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 哈希与随机令牌生成纯函数。
 */
object Hashing {

    private val B64 = Base64.getUrlEncoder().withoutPadding()
    private const val TOKEN_BYTES = 32 // 256-bit token

    /** refresh token 等凭证必须用 CSPRNG——ThreadLocalRandom 状态可由少量输出反推。 */
    private val rng = SecureRandom()

    /** SHA-256 → base64url 小写字符串。 */
    fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return B64.encodeToString(hash).lowercase()
    }

    /** 生成随机 256-bit token（base64url，SecureRandom）。 */
    fun randomTokenBase64Url(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        rng.nextBytes(bytes)
        return B64.encodeToString(bytes)
    }
}
