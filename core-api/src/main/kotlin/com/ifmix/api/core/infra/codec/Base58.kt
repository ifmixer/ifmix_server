package com.ifmix.api.core.infra.codec

import java.math.BigInteger
import java.util.UUID

/**
 * Base58 编解码（Bitcoin 字母表，无 0/O/I/l，全 URL-safe）。
 * UUID (16 bytes) 编码后为 22 位字符串。
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        var num = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (div, rem) = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[rem.toInt()])
            num = div
        }
        // 前导零字节 → 前导 '1'
        for (b in bytes) {
            if (b.toInt() == 0) sb.append(ALPHABET[0]) else break
        }
        return sb.reverse().toString()
    }

    fun decode(str: String): ByteArray {
        var num = BigInteger.ZERO
        for (c in str) {
            val digit = DECODE_TABLE.getOrElse(c.code) { -1 }
            require(digit >= 0) { "Invalid Base58 character: $c" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray()
        // BigInteger 可能多一个前导 0x00 符号位
        val stripped = if (bytes.size > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
        // 前导 '1' → 前导 0x00
        val leadingZeros = str.takeWhile { it == ALPHABET[0] }.length
        return ByteArray(leadingZeros) + stripped
    }
}

/** UUID → Base58 (22 chars) */
fun UUID.toBase58(): String {
    val bytes = ByteArray(16)
    var msb = this.mostSignificantBits
    var lsb = this.leastSignificantBits
    for (i in 0..7) {
        bytes[7 - i] = (msb and 0xFF).toByte()
        msb = msb ushr 8
    }
    for (i in 0..7) {
        bytes[15 - i] = (lsb and 0xFF).toByte()
        lsb = lsb ushr 8
    }
    return Base58.encode(bytes)
}

/** Base58 → UUID */
fun String.toUuidFromBase58(): UUID {
    val bytes = Base58.decode(this)
    val padded = if (bytes.size < 16) ByteArray(16 - bytes.size) + bytes else bytes
    require(padded.size == 16) { "Invalid Base58 UUID: expected 16 bytes, got ${padded.size}" }
    var msb = 0L
    var lsb = 0L
    for (i in 0..7) {
        msb = (msb shl 8) or (padded[i].toLong() and 0xFF)
    }
    for (i in 8..15) {
        lsb = (lsb shl 8) or (padded[i].toLong() and 0xFF)
    }
    return UUID(msb, lsb)
}
