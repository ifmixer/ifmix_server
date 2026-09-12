package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * auth_identity ↔ idp_identity 的 M:N 绑定（app 级）。
 * 唯一键: (projectId, authIdentityId, idpIdentityId) 在未删除记录中唯一。
 */
@Entity
@Table(name = "auth_identity_to_idpidentity_relation")
interface AuthIdentityIdpRelation : BaseProjectEntity, SoftDeletableProps {

    /** 逻辑外键 → auth_identity（app 级账号）。 */
    @Column(name = "auth_identity_id")
    val authIdentityId: UUID

    /** 逻辑外键 → auth_idpidentity（全局身份行 id）。 */
    @Column(name = "idp_identity_id")
    val idpIdentityId: UUID
}
