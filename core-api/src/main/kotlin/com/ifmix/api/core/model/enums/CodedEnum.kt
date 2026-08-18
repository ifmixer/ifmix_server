package com.ifmix.api.core.model.enums

/**
 * 带数字编码的枚举公共接口。
 *
 * 所有存 DB 的枚举实现此接口，约定：
 * - code=0 为 UNKNOWN 兜底值
 * - 正常业务编码从 100 起步，同组连续，不同组间隔 100
 */
interface CodedEnum {
    val code: Int
}

/**
 * 对象存储 bucket 标识。对应 app.storage.buckets.{id} 配置键。
 */
object StorageBucketId {
    const val UGC = "ugc"
    const val STATIC = "static"
}
