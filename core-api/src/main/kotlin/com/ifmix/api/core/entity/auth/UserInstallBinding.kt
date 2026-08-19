package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 用户-Install 绑定记录。
 * 每次登录成功时 upsert，记录用户使用了哪些设备/安装实例。
 */
@Entity
@Table(name = "core_user_install_binding")
interface UserInstallBinding : AppScopedProps, MutableProps {

    @Id
    val id: UUID

    val appId: UUID

    val userId: UUID

    val installId: UUID

    /** 首次在此 install 上登录的时间 */
    val firstSeenAt: Instant

    /** 最近一次在此 install 上登录的时间 */
    val lastSeenAt: Instant

    /** 累计登录次数 */
    val loginCount: Int

    val clientIp: String?

    val clientPlatform: String?
}
