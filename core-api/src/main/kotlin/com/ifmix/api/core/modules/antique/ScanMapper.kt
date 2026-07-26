package com.ifmix.api.core.modules.antique

import io.mcarle.konvert.api.Konverter

/**
 * Konvert 编译期生成 ScanRecordDocument <-> DTO 映射。
 *
 * 所有字段同名且同类型，Konvert 直接拷贝即可。
 * createdAt/updatedAt 同为 Instant（JSON 由 Jackson 统一序列化成 epoch 毫秒）。
 * 通过 Konverter.get<ScanMapper>() 获取生成的实现。
 */
@Konverter
interface ScanMapper {

    fun toDto(record: ScanRecordDocument): ScanDto

    fun toListItemDto(record: ScanRecordDocument): ScanListItemDto

    companion object
}
