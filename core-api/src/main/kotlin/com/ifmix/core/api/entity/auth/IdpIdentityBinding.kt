package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseAppEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Actor 绑定了哪些 IDP 身份（app 级）。
 * 唯一键: (appId, idpIdentityId) 在未删除记录中唯一。
 */
@Entity
@Table(name = "auth_actor_to_idpidentity_relation")
interface IdpIdentityBinding : BaseAppEntity, SoftDeletableProps {

    /** 主体类型（10:customer / 20:manager，见 AuthJwtService）。 */
    @Column(name = "actor_type")
    val actorType: Int

    /** 逻辑外键 → 主体 id（actorType==customer 时为 customer.id）。 */
    @Column(name = "actor_id")
    val actorId: UUID

    /** 逻辑外键 → auth_idp */
    val idpId: UUID

    /** 逻辑外键 → auth_idpidentity（该行 id，非第三方 subject） */
    val idpIdentityId: UUID
}
