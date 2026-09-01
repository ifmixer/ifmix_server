package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Customer 绑定了哪些 IDP 身份（app 级）。
 * 唯一键: (appId, idpIdentityId) 在未删除记录中唯一。
 * 表名保持 auth_appuser_to_idpidentity_relation（避免动表）。
 */
@Entity
@Table(name = "auth_appuser_to_idpidentity_relation")
interface IdpIdentityBinding : BaseAppEntity, SoftDeletableProps {

    /** 逻辑外键 → customer（跨模块）。列名保持 app_user_id（避免动表）。 */
    @Column(name = "app_user_id")
    val customerId: UUID

    /** 逻辑外键 → auth_idp */
    val idpId: UUID

    /** 逻辑外键 → auth_idpidentity */
    val idpIdentityId: UUID
}
