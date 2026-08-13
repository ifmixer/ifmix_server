package com.ifmix.api.core.common.modules.app.repo

import com.ifmix.api.core.common.entity.appconfig.AppInfo
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseCrudRepository
import org.springframework.stereotype.Repository

@Repository
class AppInfoRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AppInfo>(clusterRegistry, AppInfo::class)
