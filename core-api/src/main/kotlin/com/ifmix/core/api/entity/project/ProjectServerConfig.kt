package com.ifmix.core.api.entity.project

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*

/**
 * 服务端专属项目配置（**绝不下发前端**，与 core_project_configrevision 分开正是为此）。
 * 每 project 一行（projectId 唯一）。存 FCM service account 等敏感凭据。
 */
@Entity
@Table(name = "core_project_serverconfig")
interface ProjectServerConfig : BaseProjectEntity {

    /**
     * FCM 配置（JSONB）。内容为该 project 的 Firebase service account JSON（整体）。
     * 无此配置 = 该 project 不发 FCM push（走 noop 效果）。不含 enabled——是否发由 feature flag 控制。
     */
    @Serialized
    @Column(name = "fcm_config")
    val fcmConfig: Map<String, Any?>?

    /**
     * 平台证明（App Attest / Play Integrity）配置（JSONB）。null = 该 project 未启用证明。
     * 含 DeviceCheck .p8 私钥 / Play service account 等敏感凭据，与 fcmConfig 同受「绝不下发前端」约束。
     */
    @Serialized
    @Column(name = "app_attest_config")
    val appAttestConfig: Map<String, Any?>?
}
