package com.ifmix.api.core.entity.user

import com.ifmix.api.core.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*

/**
 * 设备安装记录。每次客户端首次启动时注册，获取 installId + JWT。
 */
@Entity
@Table(name = "user_install")
interface Install : BaseAppEntity {

    /** 平台: 10=iOS, 20=Android, 30=Web */
    val platform: Int

    /** 原生版本号 */
    val nativeVersion: String?

    /** JS bundle 版本号 */
    val jsVersion: String?

    /** 注册时的客户端 IP */
    val clientIp: String?

    /** 扩展信息 (device model, os version 等) */
    @Serialized
    @Column(name = "info")
    val info: Map<String, Any?>?
}
