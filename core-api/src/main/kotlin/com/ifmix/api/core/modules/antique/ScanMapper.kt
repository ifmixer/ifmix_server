package com.ifmix.api.core.modules.antique

/**
 * Konvert 编译期生成 ScanRecordDocument <-> DTO 映射。
 *
 * 所有字段同名且同类型，Konvert 直接拷贝即可。
 * createdAt/updatedAt 同为 Instant（JSON 由 Jackson 统一序列化成 epoch 毫秒）。
 * ObjectId → String 转换通过自定义 TypeConverter（注册在 META-INF/services）自动完成。
 * 通过 Konverter.get<ScanMapper>() 获取生成的实现。
 */
object ScanMapper {

    fun toDto(record: ScanRecordDocument): ScanDto = ScanDto(
        id = record.id.toHexString(),
        scanId = record.scanId,
        imageUrl = record.imageUrl,
        status = record.status,
        resultJson = record.resultJson,
        tier = record.tier,
        clientIp = record.clientIp,
        relatedId = record.relatedId,
        userId = record.userId,
        installId = record.installId,
        collected = record.collected,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
    )

    fun toListItemDto(record: ScanRecordDocument): ScanListItemDto = ScanListItemDto(
        id = record.id.toHexString(),
        scanId = record.scanId,
        status = record.status,
        imageUrl = record.imageUrl,
        createdAt = record.createdAt,
    )
}
