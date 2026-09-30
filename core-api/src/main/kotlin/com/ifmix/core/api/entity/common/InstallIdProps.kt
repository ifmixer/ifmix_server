package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

/**
 * 可信安装标识快照：install_id 来自已验签 token 的 iid（= core_install.id），
 * 由 [com.ifmix.core.api.infra.http.ActionContext.mustGetTokenInstallId] 提供，服务端可信。
 * 列在 legacy 迁移期间为 nullable(旧行可能为 null);新写入必须非空(应用层 mustGetTokenInstallId 保证,V7 SET NOT NULL 收尾锁定)。
 * 注意：可伪造的 x-install-id header 不写入此列。
 */
@MappedSuperclass
interface InstallIdProps {
    @Column(name = "install_id")
    val installId: UUID?
}
