package com.ifmix.api.core.modules.antique

import io.mcarle.konvert.api.GeneratedKonverter

public object ScanMapperImpl : ScanMapper {
  @GeneratedKonverter(priority = 5_000)
  override fun toDto(record: ScanRecordEntity): ScanDto = ScanDto(
    id = record.id,
    scanId = record.scanId,
    imageUrl = record.imageUrl,
    status = record.status,
    resultJson = record.resultJson,
    tier = record.tier,
    clientIp = record.clientIp,
    relatedId = record.relatedId,
    createdAt = record.createdAt,
    updatedAt = record.updatedAt
  )

  @GeneratedKonverter(priority = 5_000)
  override fun toListItemDto(record: ScanRecordEntity): ScanListItemDto = ScanListItemDto(
    id = record.id,
    scanId = record.scanId,
    status = record.status,
    imageUrl = record.imageUrl,
    createdAt = record.createdAt
  )
}
