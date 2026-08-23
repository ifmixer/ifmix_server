package com.ifmix.api.core.infra.codec

import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID

/**
 * UUID ↔ Base58 编码（Bitcoin 字母表，22 字符，URL-safe）。
 * 仅用于 objectKey 等 URL 场景，API 输入输出仍用原始 36 字符格式。
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(uuid: UUID): String {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        var num = BigInteger(1, buf.array())
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (div, rem) = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[rem.toInt()])
            num = div
        }
        return sb.reverse().toString().padStart(22, '1')
    }

    fun decode(encoded: String): UUID {
        var num = BigInteger.ZERO
        for (c in encoded) {
            val digit = ALPHABET.indexOf(c)
            require(digit >= 0) { "invalid Base58 character: $c" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray()
        val padded = ByteArray(16)
        val offset = if (bytes.size > 16) bytes.size - 16 else 0
        val destOffset = if (bytes.size < 16) 16 - bytes.size else 0
        System.arraycopy(bytes, offset, padded, destOffset, minOf(bytes.size, 16))
        val buf = ByteBuffer.wrap(padded)
        return UUID(buf.getLong(), buf.getLong())
    }
}

fun UUID.toBase58(): String = Base58.encode(this)
fun String.toUuidFromBase58(): UUID = Base58.decode(this)
