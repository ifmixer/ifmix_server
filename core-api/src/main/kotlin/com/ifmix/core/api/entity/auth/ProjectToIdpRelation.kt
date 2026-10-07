package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 启用了哪些 IDP（app 级）。
 */
@Entity
@Table(name = "core_auth_project2idp")
interface ProjectToIdpRelation : BaseProjectEntity, SoftDeletableProps {

    /** 逻辑外键 → auth_idp */
    val idpId: UUID
}
