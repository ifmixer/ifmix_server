package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.MappedSuperclass

/**
 * 客户端版本快照：由 x-app-version / x-ota-version header 上报，服务端仅记录（原样透传，不校验格式）。
 * 用于按版本聚合分析 / 灰度回归对比 / 定位某 OTA 引入的问题。可空。
 *
 * - appVersion：App 版本号，如 `1.2.3`
 * - otaVersion：热更新版本号，形如 `${'$'}{runtimeVersion}-${'$'}{buildNumber}-${'$'}{otaSeq}`，如 `1-23-3`
 */
@MappedSuperclass
interface ClientVersionProps {
    @Column(name = "app_version")
    val appVersion: String?

    @Column(name = "ota_version")
    val otaVersion: String?
}
