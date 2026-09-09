package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.MappedSuperclass

/**
 * 安装标识快照：installId 由客户端生成、经 x-install-id header 上报，服务端仅记录（可伪造，不用于鉴权）。
 * 用于后续行为分析 / 跨匿名会话归因。所有 customer 侧数据表统一带上。
 */
@MappedSuperclass
interface InstallIdProps {
    @Column(name = "install_id")
    val installId: String?
}
