package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * AppUser 绑定了哪些 IDP 身份（app 级）。
 * 唯一键: (appId, idpIdentityId) 在未删除记录中唯一。
 */
@Entity
@Table(name = "auth_appuser_to_idpidentity_relation")
interface AppUserToIdpIdentityRelation : AppScopedProps, MutableProps, SoftDeletableProps {
    @Id
    val id: UUID

    /** 逻辑外键 → user_appuser（跨模块） */
    val appUserId: UUID

    /** 逻辑外键 → auth_idp */
    val idpId: UUID

    /** 逻辑外键 → auth_idpidentity */
    val idpIdentityId: UUID
}
