package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass

/** 用户偏好快照：locale / country / currency，记录创建时的用户设置。 */
@MappedSuperclass
interface UserPreferenceProps {
    val locale: String?
    val country: String?
    val currency: String?
}
