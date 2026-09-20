package com.ifmix.core.api.modules.customer

import com.ifmix.core.api.entity.customer.Customer
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.customer.repo.CustomerRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CustomerFacade(
    private val mcFactory: ModuleCtxFactory,
    private val customerRepo: CustomerRepository,
) {
    fun findById(ctx: ActionContext, id: UUID): Customer? =
        customerRepo.findById(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    fun createCustomer(ctx: ActionContext): UUID =
        customerRepo.createCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId())

    fun exists(ctx: ActionContext, id: UUID): Boolean =
        customerRepo.exists(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)
}
