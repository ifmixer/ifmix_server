package com.ifmix.core.api.entity.auth.install

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * install ↔ customer 绑定关系。(install_id, customer_id) 全局唯一，一对一行。
 * bind/unbind 复用同一行翻转 deleted_at：null=当前绑定，not null=已解绑。
 * 一个 install 同时只绑一个 customer（业务保证：绑新的前软删该 install 其它有效关系）。
 */
@Entity
@Table(name = "core_install_customer_relation")
interface InstallCustomerRelation : BaseProjectEntity, SoftDeletableProps {

    @Column(name = "install_id")
    val installId: UUID

    @Column(name = "customer_id")
    val customerId: UUID
}
