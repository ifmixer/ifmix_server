package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.db.SvcCtx
import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class JooqConfig(private val dataSource: DataSource) {

    @Bean
    fun dslContext(): DSLContext =
        DSL.using(dataSource, SQLDialect.POSTGRES).apply {
            configuration().set(AuditRecordListener())
        }

    /** 初始化 SvcCtx.DEFAULT，供旧代码和默认单集群场景使用 */
    @PostConstruct
    fun initDefaultSvcCtx() {
        SvcCtx.DEFAULT = SvcCtx(
            op = com.ifmix.api.core.infra.http.OperationContext(
                req = com.ifmix.api.core.infra.http.RequestContext()
            ),
            dsl = dslContext(),
            clusterId = "default",
        )
    }
}
