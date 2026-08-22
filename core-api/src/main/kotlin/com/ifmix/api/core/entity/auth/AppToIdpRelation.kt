package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 启用了哪些 IDP（app 级）。
 */
@Entity
@Table(name = "auth_app_to_idp_relation")
interface AppToIdpRelation : AppScopedProps, MutableProps, SoftDeletableProps {
    @Id
    val id: UUID

    /** 逻辑外键 → auth_idp */
    val idpId: UUID
}
