package com.ifmix.core.api.infra.db

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator
import java.util.UUID

/**
 * UUIDv7 生成器。
 *
 * UUIDv7 高位包含毫秒级 Unix 时间戳，保证时间单调递增，
 * 适合作为分布式数据库主键（cursor 分页依赖 id 有序性）。
 */
object UuidV7 {

    private val generator: TimeBasedEpochGenerator = Generators.timeBasedEpochGenerator()

    /** 生成一个 UUIDv7（时间排序、全局唯一） */
    fun generate(): UUID = generator.generate()
}
