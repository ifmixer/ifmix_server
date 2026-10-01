package com.ifmix.core.api.entity.ai

/**
 * AI API Key 来源（provider）编码。
 *
 * 编码：10=AGNES（当前唯一 provider）。步长 10 为后续 provider 留空间。
 *
 * 注意：不要与 @Entity 放同一个文件——Jimmer KSP 遇到同文件其他顶层声明会静默跳过实体代码生成。
 */
object ApiProviders {
    const val AGNES = 10
}
