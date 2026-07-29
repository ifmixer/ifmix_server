package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.AntiqueService
import com.ifmix.api.core.service.antique.CreateScanRequest
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的古物扫描路由（简化版 - 直接返回实体，无 Konvert 映射）。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(com.ifmix.api.core.service.antique.AntiqueService::class)
class CustomerAntiqueController(private val antiqueService: com.ifmix.api.core.service.antique.AntiqueService) {

    /** 创建扫描任务。（待实现） */
    @PostMapping("/mutation/antique/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateScanRequest): Any =
        throw NotImplementedError("createOne not implemented")

    /** 按 ID 获取扫描记录详情。（待实现） */
    @PutMapping("/query/antique/getById")
    fun getById(ctx: RequestContext, @RequestBody req: Any): Any =
        throw NotImplementedError("getById not implemented")

    /** 游标分页查询扫描记录列表。（待实现） */
    @PutMapping("/query/antique/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<Any> =
        Page(emptyList(), null, false)

    data class ByIdRequest(val id: String?)
}
