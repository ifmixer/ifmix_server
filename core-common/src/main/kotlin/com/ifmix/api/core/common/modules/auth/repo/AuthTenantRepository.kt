package com.ifmix.api.core.common.modules.auth.repo

import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseCrudRepository
import com.ifmix.api.core.common.entity.auth.AuthTenant
import org.springframework.stereotype.Repository

/** AuthTenant repository */
@Repository
class AuthTenantRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AuthTenant>(clusterRegistry, AuthTenant::class)
