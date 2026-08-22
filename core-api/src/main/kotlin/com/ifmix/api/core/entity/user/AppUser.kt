package com.ifmix.api.core.entity.user

import com.ifmix.api.core.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*

/**
 * App 级用户。
 */
@Entity
@Table(name = "user_appuser")
interface AppUser : BaseAppEntity {

    @Serialized
    val metadata: Map<String, Any?>?
}
