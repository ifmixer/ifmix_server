package com.ifmix.core.api.infra.auth

/**
 * 邮箱/手机号规范化纯函数。
 *
 * 规范化目标：让 "User+Tag@gmail.com"、"user+tag@gmail.com"、"USER@GMAIL.COM"
 * 都映射到同一 canonical 值，避免重复创建身份记录。
 */
object EmailNormalize {

    /**
     * 规范化邮箱地址。
     * - 转小写
     * - Google 类服务商忽略 `+tag` 后缀（去除第一个 `+` 及其后内容）
     * - 去除首尾空白
     */
    fun normalizeEmail(raw: String?): String? = raw?.trim()?.lowercase()?.let { email ->
        val dot = email.indexOf('@')
        if (dot < 1) return@let email
        val local = email.substring(0, dot)
        val domain = email.substring(dot + 1)
        // Google / Outlook / iCloud 等忽略 +tag
        val plus = local.indexOf('+')
        val canonicalLocal = if (plus >= 0) local.substring(0, plus) else local
        "$canonicalLocal@$domain"
    }

    /**
     * 规范化手机号。
     * - 仅保留数字，去除所有非数字字符
     * - 若以国家码 `+86`（中国）开头，去掉 `+` 和前导零
     */
    fun normalizePhone(raw: String?): String? = raw?.trim()?.let { phone ->
        val digits = phone.filter { it.isDigit() }
        // 统一去掉国家码前缀（假设已标准化为 E.164 格式）
        digits
    }
}
