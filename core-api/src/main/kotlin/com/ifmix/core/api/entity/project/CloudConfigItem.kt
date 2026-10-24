package com.ifmix.core.api.entity.project

import com.fasterxml.jackson.databind.JsonNode
import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*

/** 云配置平台码（`platform` 列）。0=全部（默认，此列显式例外于「0 保留不用」惯例）；与 entity/common 的 `Platforms`（10=APPLE/20=GOOGLE，商店主体）无关。 */
typealias ConfigPlatform = Int

object ConfigPlatforms {
    const val ALL = 0
    const val IOS = 10
    const val ANDROID = 20
    const val WEB = 30
}

/**
 * 云配置项（客户端冷启动按 keys 拉取，调整 app 内部行为）。一个 key 一行，value 为任意 JSON。
 * 设计见 docs/design/config/cloud-config.md。敏感凭据走 core_project_serverconfig（绝不下发），勿混用。
 */
@Entity
@Table(name = "core_project_cloudconfigitem")
interface CloudConfigItem : BaseProjectEntity {

    /** 配置键。命名由客户端约定（建议 camelCase，如 scanPushEnabled） */
    val key: String

    /** 任意 JSON 值，服务端不解释语义 */
    @Serialized
    val value: JsonNode

    /** 平台定向；0=全部（默认行），单平台行覆盖同 key 的 0 行 */
    val platform: ConfigPlatform

    /** otaVersion >= min 才下发；NULL=不限下限 */
    @Column(name = "min_ota_version")
    val minOtaVersion: String?

    /** otaVersion <= max 才下发；NULL=不限上限 */
    @Column(name = "max_ota_version")
    val maxOtaVersion: String?

    /** false = 停用（不下发，保留现场） */
    val enabled: Boolean

    /** 运维备注（为什么改、何时改） */
    val note: String
}
