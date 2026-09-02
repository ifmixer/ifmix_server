package com.ifmix.core.api.infra.codec

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

    /** 任意字符串 → Base58（UTF-8 bytes 编码）。 */
    fun encodeString(text: String): String {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.isEmpty()) return ""
        // 计算前导零字节数
        val leadingZeros = data.takeWhile { it == 0.toByte() }.size
        var num = BigInteger(1, data)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (div, rem) = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[rem.toInt()])
            num = div
        }
        // 前导零用 '1' 表示
        repeat(leadingZeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    /** Base58 → 原始字符串（UTF-8）。 */
    fun decodeString(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val leadingOnes = encoded.takeWhile { it == '1' }.length
        var num = BigInteger.ZERO
        for (c in encoded) {
            val digit = ALPHABET.indexOf(c)
            require(digit >= 0) { "invalid Base58 character: $c" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray()
        // BigInteger 可能添加前导 0x00 符号位
        val stripped = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        val result = ByteArray(leadingOnes) + stripped
        return String(result, Charsets.UTF_8)
    }
}

fun UUID.toBase58(): String = Base58.encode(this)
fun String.toUuidFromBase58(): UUID = Base58.decode(this)
