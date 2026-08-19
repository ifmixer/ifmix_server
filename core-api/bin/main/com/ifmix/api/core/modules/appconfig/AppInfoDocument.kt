package com.ifmix.api.core.modules.appconfig

import com.ifmix.api.core.common.db.BaseEntity
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * app 规范注册表 + 稳定身份（_id 即客户端 x-app-id）。不软删、不版本化、不分片。
 */
@Document(collection = "app_info")
class AppInfoEntity : BaseEntity() {
    var name: String? = null
    var desc: String? = null

    @Indexed(unique = true, sparse = true)
    var slug: String? = null
}
