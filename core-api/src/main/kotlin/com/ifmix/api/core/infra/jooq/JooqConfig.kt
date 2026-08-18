package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.RequestContext
import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class JooqConfig(private val dataSource: DataSource) {

    private val dsl: DSLContext by lazy {
        DSL.using(dataSource, SQLDialect.POSTGRES).apply {
            configuration().set(AuditRecordListener())
        }
    }

    @Bean
    fun dslContext(): DSLContext = dsl

    @PostConstruct
    fun initDefaultSvcCtx() {
        SvcCtx.DEFAULT = SvcCtx(
            op = OperationContext(req = RequestContext()),
            dsl = dsl,
            clusterId = "default",
        )
    }
}
