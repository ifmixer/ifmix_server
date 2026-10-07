package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

/**
 * 可信安装标识快照：install_id 来自已验签 token 的 iid（= core_auth_install.id），
 * 由 [com.ifmix.core.api.infra.http.ActionContext.mustGetTokenInstallId] 提供，服务端可信。
 * 列仍 nullable(线上 v1.0.3 历史行可能为 null);新写入必须非空(应用层 mustGetTokenInstallId 保证;legacy 回退已于 v1.0.6 删除,SET NOT NULL 收尾迁移待建)。
 * 注意：可伪造的 x-install-id header 不写入此列。
 */
@MappedSuperclass
interface InstallIdProps {
    @Column(name = "install_id")
    val installId: UUID?
}
