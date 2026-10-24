package com.ifmix.core.api.modules.project.repo

import com.ifmix.core.api.entity.project.CloudConfigItem
import com.ifmix.core.api.entity.project.ConfigPlatform
import com.ifmix.core.api.entity.project.ConfigPlatforms
import com.ifmix.core.api.entity.project.enabled
import com.ifmix.core.api.entity.project.platform
import com.ifmix.core.api.entity.project.projectId
import com.ifmix.core.api.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository

@Repository
class CloudConfigItemRepository {

    /**
     * enabled 且平台匹配（0=全部 或 =指定平台）的行。
     * ota 版本门槛不在此过滤——逐段数值比较放内存做（行数小），SQL 保持简单。
     */
    fun findEnabledRows(mc: ModuleCtx, projectId: String, platform: ConfigPlatform?): List<CloudConfigItem> {
        return mc.sql.createQuery(CloudConfigItem::class) {
            where(table.projectId eq projectId)
            where(table.enabled eq true)
            where(
                when (platform) {
                    null -> table.platform eq ConfigPlatforms.ALL
                    else -> or(table.platform eq ConfigPlatforms.ALL, table.platform eq platform)
                }
            )
            select(table)
        }.execute()
    }
}
